package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.LibraryItemExpanded
import app.shelfie.data.PodcastEpisode
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EpisodeRowData(
    val episode: PodcastEpisode,
    val progressFraction: Float,
    val isFinished: Boolean,
)

private sealed interface EpisodesUi {
    data object Loading : EpisodesUi
    data class Error(val message: String) : EpisodesUi
    data class Ready(val podcast: LibraryItemExpanded, val episodes: List<EpisodeRowData>) : EpisodesUi
}

@Composable
fun EpisodesScreen(
    app: ShelfieApp,
    itemId: String,
    controller: MediaController?,
    playerState: PlayerUiState,
    onBack: () -> Unit,
) {
    val ui by produceState<EpisodesUi>(initialValue = EpisodesUi.Loading, itemId) {
        value = withContext(Dispatchers.IO) {
            try {
                val podcast = app.repository.podcast(itemId)
                val rows = podcast.media.episodes
                    .sortedByDescending { it.publishedAt ?: 0 }
                    .map { episode ->
                        val progress = runCatching {
                            app.repository.progress(itemId, episode.id)
                        }.getOrNull()
                        EpisodeRowData(
                            episode = episode,
                            progressFraction = (progress?.progress ?: 0.0).toFloat().coerceIn(0f, 1f),
                            isFinished = progress?.isFinished == true,
                        )
                    }
                EpisodesUi.Ready(podcast, rows)
            } catch (e: Exception) {
                EpisodesUi.Error(e.message ?: "Failed to load episodes")
            }
        }
    }

    when (val state = ui) {
        is EpisodesUi.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is EpisodesUi.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }

        is EpisodesUi.Ready -> {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    PodcastHeader(
                        podcast = state.podcast,
                        coverUrl = app.repository.coverUrl(itemId),
                        onBack = onBack,
                    )
                }
                items(state.episodes, key = { it.episode.id }) { row ->
                    EpisodeRow(
                        row = row,
                        isCurrent = playerState.mediaId == "episode:$itemId:${row.episode.id}",
                        isPlaying = playerState.isPlaying,
                        onClick = {
                            controller?.let { c ->
                                val mediaId = "episode:$itemId:${row.episode.id}"
                                if (playerState.mediaId == mediaId) {
                                    if (c.isPlaying) c.pause() else c.play()
                                } else {
                                    c.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
                                    c.prepare()
                                    c.play()
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
private fun PodcastHeader(podcast: LibraryItemExpanded, coverUrl: String, onBack: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    podcast.media.metadata.title ?: "Podcast",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                podcast.media.metadata.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${podcast.media.episodes.size} episodes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    row: EpisodeRowData,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val episode = row.episode
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                episode.title ?: "Episode",
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val durationSec = (episode.audioTrack?.duration ?: episode.audioFile?.duration ?: 0.0).toLong()
            val meta = listOf(formatDate(episode.publishedAt), formatDuration(durationSec))
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.progressFraction > 0.01f && !row.isFinished) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { row.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            row.isFinished && !isCurrent -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Finished",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )

            isCurrent && isPlaying -> Icon(
                Icons.Filled.PauseCircle,
                contentDescription = "Pause",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )

            else -> Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}
