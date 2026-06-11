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
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.PodcastEpisode
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
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

@Composable
fun LatestScreen(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
) {
    val ui by produceState<LatestUi>(initialValue = LatestUi.Loading) {
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
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.episodes, key = { it.id }) { episode ->
                    LatestEpisodeRow(
                        episode = episode,
                        progress = state.progress[episode.id],
                        podcastTitle = state.podcastTitles[episode.libraryItemId] ?: "",
                        coverUrl = app.repository.coverUrl(episode.libraryItemId),
                        isCurrent = playerState.mediaId == episodeMediaId(episode.libraryItemId, episode.id),
                        isPlaying = playerState.isPlaying,
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
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                episode.title ?: "Episode",
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val durationSec = (episode.audioTrack?.duration ?: episode.audioFile?.duration ?: 0.0).toLong()
            val meta = listOf(podcastTitle, formatDate(episode.publishedAt), formatDuration(durationSec))
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress != null && progress.fraction > 0.01f && !progress.isFinished) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(3.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (isCurrent && isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
            contentDescription = if (isCurrent && isPlaying) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
        )
    }
}
