package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.PodcastEpisode
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.CoroutineScope
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
    val progressRevision by app.repository.progressRevision.collectAsState()
    val contentVersion by app.repository.contentVersion.collectAsState()
    val refreshing by app.repository.refreshing.collectAsState()
    val scope = rememberCoroutineScope()
    val ui by produceState<LatestUi>(initialValue = LatestUi.Loading, contentVersion, progressRevision) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!app.repository.ensureConfigured()) {
                    LatestUi.Error("Not logged in")
                } else {
                    val episodes = app.repository.latestEpisodes()
                    val titles = app.repository.podcasts()
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
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { app.repository.refreshAll() } },
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
            val activeDownloads by app.downloads.active.collectAsState()
            var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }
            var selectMode by remember { mutableStateOf(false) }
            var selectedIds by remember { mutableStateOf(emptySet<String>()) }
            var showBulkPlaylist by remember { mutableStateOf(false) }

            val selectedEpisodes = state.episodes.filter { it.id in selectedIds }
            fun exitSelect() {
                selectMode = false
                selectedIds = emptySet()
            }

            pickerEntry?.let { entry ->
                PlaylistPickerDialog(app = app, entry = entry, onDismiss = { pickerEntry = null })
            }
            if (showBulkPlaylist) {
                BulkPlaylistPickerDialog(
                    app = app,
                    entries = selectedEpisodes.map { ep ->
                        PlaylistEntry(
                            itemId = ep.libraryItemId,
                            episodeId = ep.id,
                            title = ep.title ?: "Episode",
                            podcastTitle = state.podcastTitles[ep.libraryItemId] ?: "",
                        )
                    },
                    onDismiss = {
                        showBulkPlaylist = false
                        exitSelect()
                    },
                )
            }

            Column(Modifier.fillMaxSize()) {
                SelectionBar(
                    selectMode = selectMode,
                    selectedCount = selectedIds.size,
                    allSelected = state.episodes.isNotEmpty() && selectedIds.size == state.episodes.size,
                    onEnter = { selectMode = true },
                    onCancel = { exitSelect() },
                    onToggleAll = {
                        selectedIds = if (selectedIds.size == state.episodes.size) {
                            emptySet()
                        } else {
                            state.episodes.map { it.id }.toSet()
                        }
                    },
                    onBulkPlaylist = { if (selectedIds.isNotEmpty()) showBulkPlaylist = true },
                    onBulkDownload = {
                        bulkDownload(app, scope, selectedEpisodes)
                        exitSelect()
                    },
                )
                LazyColumn(Modifier.weight(1f)) {
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
                            downloadUi = downloadUiFor(app, activeDownloads, completedDownloads, episode.libraryItemId, episode.id),
                            isCurrent = playerState.mediaId == episodeMediaId(episode.libraryItemId, episode.id),
                            isPlaying = playerState.isPlaying,
                            selectMode = selectMode,
                            selected = episode.id in selectedIds,
                            onToggleSelect = {
                                selectedIds = if (episode.id in selectedIds) {
                                    selectedIds - episode.id
                                } else {
                                    selectedIds + episode.id
                                }
                            },
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
}

@Composable
private fun LatestEpisodeRow(
    episode: PodcastEpisode,
    progress: EpisodeProgressUi?,
    podcastTitle: String,
    coverUrl: String,
    downloadUi: DownloadUi,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selectMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    actions: EpisodeMenuActions,
    onClick: () -> Unit,
) {
    val completed = progress != null && isNearlyComplete(progress.fraction, progress.isFinished)
    val durationSec = (episode.audioTrack?.duration ?: episode.audioFile?.duration ?: 0.0).toLong()
    val dateLine = listOf(
        formatEpisodeDate(episode.publishedAt, episode.pubDate),
        formatDuration(durationSec),
    )
        .filter { it.isNotBlank() }
        .joinToString(" • ")

    val rowContent: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (selectMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Spacer(Modifier.width(4.dp))
            }
            EpisodeRowContent(
                coverUrl = coverUrl,
                title = episode.title ?: "Episode",
                subtitle = podcastTitle,
                dateLine = dateLine,
                progressFraction = progress?.fraction ?: 0f,
                completed = completed,
                titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                downloadUi = downloadUi,
            )
            if (!selectMode) {
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

    if (selectMode) {
        Box(Modifier.fillMaxWidth().clickable { onToggleSelect() }) { rowContent() }
    } else {
        EpisodeLongPressBox(onClick = onClick, actions = actions, modifier = Modifier.fillMaxWidth()) {
            rowContent()
        }
    }
}
