package app.shelfie.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.shelfie.ShelfieApp
import app.shelfie.data.PodcastEpisode
import app.shelfie.download.ActiveDownload
import app.shelfie.download.DownloadedEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shared cover + text block for every episode list row (Latest, Playlist,
 * podcast detail, Search), so they all look identical: square cover, a title
 * that wraps onto further lines until it fits, then the podcast name and the
 * publish date/time each on their own line, and a thin progress bar.
 *
 * Call inside a [androidx.compose.foundation.layout.Row].
 */
@Composable
fun RowScope.EpisodeRowContent(
    coverUrl: String,
    title: String,
    subtitle: String?,
    dateLine: String,
    progressFraction: Float,
    completed: Boolean,
    titleColor: Color = Color.Unspecified,
    downloadUi: DownloadUi = DownloadUi.None,
) {
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
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = titleColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (dateLine.isNotBlank()) {
            Text(
                dateLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (val d = downloadUi) {
            is DownloadUi.InProgress -> {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.fraction > 0f) {
                        CircularProgressIndicator(
                            progress = { d.fraction },
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (d.fraction > 0f) "Downloading ${(d.fraction * 100).toInt()}%" else "Downloading…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            DownloadUi.Done -> {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.DownloadDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            DownloadUi.None -> {}
        }
        if (progressFraction > 0.01f && !completed) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
    }
}

/** Options shown in an episode's long-press context menu. */
class EpisodeMenuActions(
    val isFinished: Boolean,
    val isDownloaded: Boolean,
    val onResetProgress: () -> Unit,
    val onToggleFinished: () -> Unit,
    val onAddToPlaylist: () -> Unit,
    /** When null, the "Go to podcast" entry is hidden (e.g. already on it). */
    val onGoToPodcast: (() -> Unit)?,
    val onToggleDownload: () -> Unit,
)

/**
 * Wraps an episode element so a normal tap runs [onClick] and a long-press
 * opens a context menu of [actions].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeLongPressBox(
    onClick: () -> Unit,
    actions: EpisodeMenuActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Box(
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
        ) {
            content()
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(x = 8.dp, y = 0.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Reset listen time") },
                leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    actions.onResetProgress()
                },
            )
            DropdownMenuItem(
                text = { Text(if (actions.isFinished) "Mark as unplayed" else "Mark as finished") },
                leadingIcon = {
                    Icon(
                        if (actions.isFinished) Icons.Filled.RemoveDone else Icons.Filled.DoneAll,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuOpen = false
                    actions.onToggleFinished()
                },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    actions.onAddToPlaylist()
                },
            )
            actions.onGoToPodcast?.let { goToPodcast ->
                DropdownMenuItem(
                    text = { Text("Go to podcast") },
                    leadingIcon = { Icon(Icons.Filled.Podcasts, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        goToPodcast()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (actions.isDownloaded) "Remove download" else "Download") },
                leadingIcon = {
                    Icon(
                        if (actions.isDownloaded) Icons.Filled.DeleteOutline else Icons.Filled.Download,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuOpen = false
                    actions.onToggleDownload()
                },
            )
        }
    }
}

/** Resets an episode's listening progress back to zero. */
fun resetEpisodeProgress(
    app: ShelfieApp,
    scope: CoroutineScope,
    itemId: String,
    episodeId: String,
    durationSec: Double,
) {
    scope.launch(Dispatchers.IO) {
        runCatching { app.repository.resetProgress(itemId, episodeId, durationSec) }
    }
}

/** Marks an episode finished (or back to unplayed). */
fun setEpisodeFinished(
    app: ShelfieApp,
    scope: CoroutineScope,
    itemId: String,
    episodeId: String,
    finished: Boolean,
    durationSec: Double,
) {
    scope.launch(Dispatchers.IO) {
        runCatching { app.repository.setFinished(itemId, episodeId, finished, durationSec) }
    }
}

/**
 * Header bar for multi-select episode lists: a "Select" entry button, and in
 * select mode a count, Select-all/None toggle, and bulk download / add-to-playlist.
 */
@Composable
fun SelectionBar(
    selectMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onEnter: () -> Unit,
    onCancel: () -> Unit,
    onToggleAll: () -> Unit,
    onBulkPlaylist: () -> Unit,
    onBulkDownload: () -> Unit,
) {
    if (!selectMode) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = onEnter) {
                Icon(Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Select")
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
            }
            Text("$selectedCount selected", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleAll) {
                Text(if (allSelected) "None" else "All")
            }
            IconButton(onClick = onBulkPlaylist, enabled = selectedCount > 0) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = "Add selected to playlist")
            }
            IconButton(onClick = onBulkDownload, enabled = selectedCount > 0) {
                Icon(Icons.Filled.Download, contentDescription = "Download selected")
            }
        }
    }
}

/** Downloads a batch of episodes for offline use. */
fun bulkDownload(
    app: ShelfieApp,
    scope: CoroutineScope,
    episodes: List<PodcastEpisode>,
) {
    scope.launch(Dispatchers.IO) {
        episodes.groupBy { it.libraryItemId }.forEach { (itemId, eps) ->
            runCatching {
                val podcast = app.repository.podcast(itemId)
                eps.forEach { ep ->
                    podcast.media.episodes.firstOrNull { it.id == ep.id }
                        ?.let { app.downloads.download(podcast, it) }
                }
            }
        }
    }
}

/** Current download state for an episode, from the active/completed flows. */
fun downloadUiFor(
    app: ShelfieApp,
    active: Map<String, ActiveDownload>,
    completed: List<DownloadedEpisode>,
    itemId: String,
    episodeId: String,
): DownloadUi {
    val key = app.downloads.key(itemId, episodeId)
    return when {
        completed.any { it.itemId == itemId && it.episodeId == episodeId } -> DownloadUi.Done
        active.containsKey(key) -> DownloadUi.InProgress(active[key]?.fraction ?: 0f)
        else -> DownloadUi.None
    }
}

/** Downloads a batch of episodes given their (itemId, episodeId) pairs. */
fun bulkDownloadByIds(
    app: ShelfieApp,
    scope: CoroutineScope,
    items: List<Pair<String, String>>,
) {
    scope.launch(Dispatchers.IO) {
        items.groupBy { it.first }.forEach { (itemId, pairs) ->
            runCatching {
                val podcast = app.repository.podcast(itemId)
                pairs.forEach { (_, episodeId) ->
                    podcast.media.episodes.firstOrNull { it.id == episodeId }
                        ?.let { app.downloads.download(podcast, it) }
                }
            }
        }
    }
}

/** Downloads an episode for offline use, or removes the local copy. */
fun toggleEpisodeDownload(
    app: ShelfieApp,
    scope: CoroutineScope,
    itemId: String,
    episodeId: String,
    isDownloaded: Boolean,
) {
    if (isDownloaded) {
        app.downloads.completed.value
            .firstOrNull { it.itemId == itemId && it.episodeId == episodeId }
            ?.let { app.downloads.delete(it) }
    } else {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val podcast = app.repository.podcast(itemId)
                podcast.media.episodes.firstOrNull { it.id == episodeId }
                    ?.let { app.downloads.download(podcast, it) }
            }
        }
    }
}
