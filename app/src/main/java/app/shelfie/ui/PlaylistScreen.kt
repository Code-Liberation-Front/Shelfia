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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.playlist.PlaylistEntry
import coil.compose.AsyncImage

@Composable
fun PlaylistScreen(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val custom by app.playlist.entries.collectAsState()
    val downloaded by app.downloads.completed.collectAsState()

    val rows: List<PlaylistEntry> = if (selectedTab == 0) {
        custom
    } else {
        downloaded
            .sortedByDescending { it.downloadedAt }
            .map { PlaylistEntry(it.itemId, it.episodeId, it.title, it.podcastTitle) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("My Playlist") },
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.DownloadDone,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Downloaded")
                    }
                },
            )
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (selectedTab == 0) {
                        "Your playlist is empty. Add episodes with the playlist button on any episode."
                    } else {
                        "No downloaded episodes yet. Downloads appear here automatically for offline listening."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Button(
            onClick = { controller?.playEntries(rows, 0) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Play all (${rows.size})")
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { _, e -> "${e.itemId}:${e.episodeId}" }) { index, entry ->
                PlaylistRow(
                    entry = entry,
                    coverUrl = app.repository.coverUrl(entry.itemId),
                    isCurrent = playerState.mediaId == episodeMediaId(entry.itemId, entry.episodeId),
                    removable = selectedTab == 0,
                    onClick = { controller?.playEntries(rows, index) },
                    onRemove = { app.playlist.remove(entry.itemId, entry.episodeId) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

private fun MediaController.playEntries(entries: List<PlaylistEntry>, startIndex: Int) {
    if (entries.isEmpty()) return
    val items = entries.map {
        MediaItem.Builder().setMediaId(episodeMediaId(it.itemId, it.episodeId)).build()
    }
    setMediaItems(items, startIndex.coerceIn(0, items.size - 1), C.TIME_UNSET)
    prepare()
    play()
}

@Composable
private fun PlaylistRow(
    entry: PlaylistEntry,
    coverUrl: String,
    isCurrent: Boolean,
    removable: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
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
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.podcastTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (removable) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.PlaylistRemove,
                    contentDescription = "Remove from playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
