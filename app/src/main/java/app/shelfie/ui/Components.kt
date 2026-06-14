package app.shelfie.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.shelfie.ShelfieApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single-line title that auto-scrolls (marquee) when it is wider than the
 * space available, and sits still otherwise.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        modifier = modifier.basicMarquee(),
    )
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
