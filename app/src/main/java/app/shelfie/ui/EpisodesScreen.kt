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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.LibraryItemExpanded
import app.shelfie.data.PodcastEpisode
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EpisodeRowData(
    val episode: PodcastEpisode,
    val progressFraction: Float,
    val isFinished: Boolean,
)

sealed interface DownloadUi {
    data object None : DownloadUi
    data class InProgress(val fraction: Float) : DownloadUi
    data object Done : DownloadUi
}

private sealed interface EpisodesUi {
    data object Loading : EpisodesUi
    data class Error(val message: String) : EpisodesUi
    data class Ready(
        val podcast: LibraryItemExpanded,
        val episodes: List<EpisodeRowData>,
        val bookFinished: Boolean = false,
    ) : EpisodesUi
}

@Composable
fun EpisodesScreen(
    app: ShelfieApp,
    itemId: String,
    controller: MediaController?,
    playerState: PlayerUiState,
    onBack: () -> Unit,
) {
    val progressRevision by app.repository.progressRevision.collectAsState()
    val ui by produceState<EpisodesUi>(initialValue = EpisodesUi.Loading, itemId, progressRevision) {
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
                // Audiobook/MP3 items have a single whole-item progress.
                val bookFinished = if (podcast.media.episodes.isEmpty() && podcast.media.tracks.isNotEmpty()) {
                    runCatching { app.repository.bookProgress(itemId)?.isFinished == true }.getOrDefault(false)
                } else {
                    false
                }
                EpisodesUi.Ready(podcast, rows, bookFinished)
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
            val scope = rememberCoroutineScope()
            val completedDownloads by app.downloads.completed.collectAsState()
            val activeDownloads by app.downloads.active.collectAsState()
            var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }
            var selectMode by remember { mutableStateOf(false) }
            var selectedIds by remember { mutableStateOf(emptySet<String>()) }
            var showBulkPlaylist by remember { mutableStateOf(false) }

            val episodeRows = state.episodes
            val selectedEpisodes = episodeRows.filter { it.episode.id in selectedIds }.map { it.episode }
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
                            itemId = itemId,
                            episodeId = ep.id,
                            title = ep.title ?: "Episode",
                            podcastTitle = state.podcast.media.metadata.title ?: "",
                        )
                    },
                    onDismiss = {
                        showBulkPlaylist = false
                        exitSelect()
                    },
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    PodcastHeader(
                        podcast = state.podcast,
                        coverUrl = app.repository.coverUrl(itemId),
                        onBack = onBack,
                    )
                }
                if (episodeRows.isNotEmpty()) {
                    item {
                        SelectionBar(
                            selectMode = selectMode,
                            selectedCount = selectedIds.size,
                            allSelected = selectedIds.size == episodeRows.size,
                            onEnter = { selectMode = true },
                            onCancel = { exitSelect() },
                            onToggleAll = {
                                selectedIds = if (selectedIds.size == episodeRows.size) {
                                    emptySet()
                                } else {
                                    episodeRows.map { it.episode.id }.toSet()
                                }
                            },
                            onBulkPlaylist = { if (selectedIds.isNotEmpty()) showBulkPlaylist = true },
                            onBulkDownload = {
                                selectedEpisodes.forEach { app.downloads.download(state.podcast, it) }
                                exitSelect()
                            },
                        )
                    }
                }
                // Audiobook/MP3 items have tracks instead of episodes; their
                // progress (and the long-press menu) is for the whole item.
                if (state.episodes.isEmpty() && state.podcast.media.tracks.isNotEmpty()) {
                    val bookDuration = state.podcast.media.tracks.sumOf { it.duration }
                    itemsIndexed(state.podcast.media.tracks) { index, track ->
                        TrackRow(
                            title = track.title ?: "Part ${index + 1}",
                            durationSec = track.duration.toLong(),
                            isCurrent = playerState.mediaId == trackMediaId(itemId, index),
                            isPlaying = playerState.isPlaying,
                            actions = EpisodeMenuActions(
                                isFinished = state.bookFinished,
                                isDownloaded = false,
                                onResetProgress = {
                                    resetEpisodeProgress(app, scope, itemId, "", bookDuration)
                                },
                                onToggleFinished = {
                                    setEpisodeFinished(
                                        app, scope, itemId, "",
                                        finished = !state.bookFinished,
                                        durationSec = bookDuration,
                                    )
                                },
                            ),
                            onClick = {
                                controller?.let { c ->
                                    if (playerState.mediaId == trackMediaId(itemId, index)) {
                                        if (c.isPlaying) c.pause() else c.play()
                                    } else {
                                        c.playTrack(itemId, index)
                                    }
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                items(state.episodes, key = { it.episode.id }) { row ->
                    val durationSec = (row.episode.audioTrack?.duration ?: row.episode.audioFile?.duration ?: 0.0)
                    val isDownloaded = completedDownloads.any {
                        it.itemId == itemId && it.episodeId == row.episode.id
                    }
                    EpisodeRow(
                        row = row,
                        coverUrl = app.repository.coverUrl(itemId),
                        downloadUi = downloadUiFor(app, activeDownloads, completedDownloads, itemId, row.episode.id),
                        selectMode = selectMode,
                        selected = row.episode.id in selectedIds,
                        onToggleSelect = {
                            selectedIds = if (row.episode.id in selectedIds) {
                                selectedIds - row.episode.id
                            } else {
                                selectedIds + row.episode.id
                            }
                        },
                        actions = EpisodeMenuActions(
                            isFinished = row.isFinished,
                            isDownloaded = isDownloaded,
                            onResetProgress = {
                                resetEpisodeProgress(app, scope, itemId, row.episode.id, durationSec)
                            },
                            onToggleFinished = {
                                setEpisodeFinished(app, scope, itemId, row.episode.id, finished = !row.isFinished, durationSec = durationSec)
                            },
                            onAddToPlaylist = {
                                pickerEntry = PlaylistEntry(
                                    itemId = itemId,
                                    episodeId = row.episode.id,
                                    title = row.episode.title ?: "Episode",
                                    podcastTitle = state.podcast.media.metadata.title ?: "",
                                )
                            },
                            onGoToPodcast = null,
                            onToggleDownload = {
                                toggleEpisodeDownload(app, scope, itemId, row.episode.id, isDownloaded)
                            },
                        ),
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
            CoverImage(
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
                podcast.media.metadata.displayAuthor?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val countText = when {
                    podcast.media.episodes.isNotEmpty() -> "${podcast.media.episodes.size} episodes"
                    podcast.media.tracks.isNotEmpty() -> "${podcast.media.tracks.size} parts"
                    else -> ""
                }
                if (countText.isNotBlank()) {
                    Text(
                        countText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    title: String,
    durationSec: Long,
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val duration = formatDuration(durationSec)
                if (duration.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        duration,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                if (isCurrent && isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                contentDescription = if (isCurrent && isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    row: EpisodeRowData,
    coverUrl: String,
    downloadUi: DownloadUi,
    selectMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    actions: EpisodeMenuActions,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val episode = row.episode
    val completed = isNearlyComplete(row.progressFraction, row.isFinished)
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (selectMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Spacer(Modifier.width(4.dp))
            }
            EpisodeRowContent(
                coverUrl = coverUrl,
                title = episode.title ?: "Episode",
                subtitle = null,
                dateLine = dateLine,
                progressFraction = row.progressFraction,
                completed = completed,
                titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                downloadUi = downloadUi,
            )
            if (!selectMode) {
                Spacer(Modifier.width(4.dp))
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
    }

    if (selectMode) {
        Box(Modifier.fillMaxWidth().clickable { onToggleSelect() }) { rowContent() }
    } else {
        EpisodeLongPressBox(onClick = onClick, actions = actions, modifier = Modifier.fillMaxWidth()) {
            rowContent()
        }
    }
}
