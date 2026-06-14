package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.PodcastEpisode
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EpisodeProgressUi(val fraction: Float, val isFinished: Boolean)

private sealed interface LatestUi {
    data object Loading : LatestUi
    data class Error(val message: String) : LatestUi
    data class Ready(
        val episodes: List<PodcastEpisode>,
        val podcastTitles: Map<String, String>,
        val progress: Map<String, EpisodeProgressUi>,
    ) : LatestUi
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LatestScreen(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
    onOpenPodcast: (String) -> Unit,
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    val progressRevision by app.repository.progressRevision.collectAsState()
    val ui by produceState<LatestUi>(initialValue = LatestUi.Loading, refreshKey, progressRevision) {
        val force = refreshKey > 0
        value = withContext(Dispatchers.IO) {
            try {
                if (!app.repository.ensureConfigured()) {
                    LatestUi.Error("Not logged in")
                } else {
                    val episodes = app.repository.latestEpisodes(forceRefresh = force)
                    val titles = app.repository.podcasts(forceRefresh = force)
                        .associate { it.id to (it.media.metadata.title ?: "") }
                    val progress = episodes.associate { episode ->
                        val saved = runCatching {
                            app.repository.progress(episode.libraryItemId, episode.id)
                        }.getOrNull()
                        episode.id to EpisodeProgressUi(
                            fraction = (saved?.progress ?: 0.0).toFloat().coerceIn(0f, 1f),
                            isFinished = saved?.isFinished == true,
                        )
                    }
                    LatestUi.Ready(episodes, titles, progress)
                }
            } catch (e: Exception) {
                LatestUi.Error(e.message ?: "Failed to load latest episodes")
            }
        }
        isRefreshing = false
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            refreshKey++
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LatestContent(app, controller, playerState, onOpenPodcast, ui)
    }
}

@Composable
private fun LatestContent(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
    onOpenPodcast: (String) -> Unit,
    ui: LatestUi,
) {
    when (val state = ui) {
        is LatestUi.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is LatestUi.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }

        is LatestUi.Ready -> {
            val scope = rememberCoroutineScope()
            val completedDownloads by app.downloads.completed.collectAsState()
            var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }

            pickerEntry?.let { entry ->
                PlaylistPickerDialog(
                    app = app,
                    entry = entry,
                    onDismiss = { pickerEntry = null },
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(state.episodes, key = { it.id }) { episode ->
                    val podcastTitle = state.podcastTitles[episode.libraryItemId] ?: ""
                    val durationSec = (episode.audioTrack?.duration ?: episode.audioFile?.duration ?: 0.0)
                    val isDownloaded = completedDownloads.any {
                        it.itemId == episode.libraryItemId && it.episodeId == episode.id
                    }
                    LatestEpisodeRow(
                        episode = episode,
                        progress = state.progress[episode.id],
                        podcastTitle = podcastTitle,
                        coverUrl = app.repository.coverUrl(episode.libraryItemId),
                        isCurrent = playerState.mediaId == episodeMediaId(episode.libraryItemId, episode.id),
                        isPlaying = playerState.isPlaying,
                        actions = EpisodeMenuActions(
                            isFinished = state.progress[episode.id]?.isFinished == true,
                            isDownloaded = isDownloaded,
                            onResetProgress = {
                                resetEpisodeProgress(app, scope, episode.libraryItemId, episode.id, durationSec)
                            },
                            onToggleFinished = {
                                setEpisodeFinished(
                                    app, scope, episode.libraryItemId, episode.id,
                                    finished = state.progress[episode.id]?.isFinished != true,
                                    durationSec = durationSec,
                                )
                            },
                            onAddToPlaylist = {
                                pickerEntry = PlaylistEntry(
                                    itemId = episode.libraryItemId,
                                    episodeId = episode.id,
                                    title = episode.title ?: "Episode",
                                    podcastTitle = podcastTitle,
                                )
                            },
                            onGoToPodcast = { onOpenPodcast(episode.libraryItemId) },
                            onToggleDownload = {
                                toggleEpisodeDownload(app, scope, episode.libraryItemId, episode.id, isDownloaded)
                            },
                        ),
                        onClick = {
                            controller?.let { c ->
                                if (playerState.mediaId == episodeMediaId(episode.libraryItemId, episode.id)) {
                                    if (c.isPlaying) c.pause() else c.play()
                                } else {
                                    c.playEpisode(episode.libraryItemId, episode.id)
                                }
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LatestEpisodeRow(
    episode: PodcastEpisode,
    progress: EpisodeProgressUi?,
    podcastTitle: String,
    coverUrl: String,
    isCurrent: Boolean,
    isPlaying: Boolean,
    actions: EpisodeMenuActions,
    onClick: () -> Unit,
) {
    EpisodeLongPressBox(onClick = onClick, actions = actions, modifier = Modifier.fillMaxWidth()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val completed = progress != null && isNearlyComplete(progress.fraction, progress.isFinished)
        CoverImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            completed = completed,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            MarqueeText(
                episode.title ?: "Episode",
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            if (podcastTitle.isNotBlank()) {
                Text(
                    podcastTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val durationSec = (episode.audioTrack?.duration ?: episode.audioFile?.duration ?: 0.0).toLong()
            val dateLine = listOf(
                formatEpisodeDate(episode.publishedAt, episode.pubDate),
                formatDuration(durationSec),
            )
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            if (dateLine.isNotBlank()) {
                Text(
                    dateLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress != null && progress.fraction > 0.01f && !completed) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(3.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            if (isCurrent && isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
            contentDescription = if (isCurrent && isPlaying) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
        )
    }
    }
}
