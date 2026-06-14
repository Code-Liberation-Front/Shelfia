package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val DOWNLOADED_PLAYLIST_ID = "__downloaded__"

private data class PlaylistRowMeta(
    val fraction: Float,
    val isFinished: Boolean,
    val publishDate: String,
    val durationSec: Double,
)

private data class EpisodeInfo(
    val publishedAt: Long?,
    val pubDate: String?,
    val durationSec: Double,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    app: ShelfieApp,
    controller: MediaController?,
    playerState: PlayerUiState,
    onOpenPodcast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val playlists by app.playlist.playlists.collectAsState()
    val downloaded by app.downloads.completed.collectAsState()
    val activeDownloads by app.downloads.active.collectAsState()
    val progressRevision by app.repository.progressRevision.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf(DOWNLOADED_PLAYLIST_ID) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf<String?>(null) }
    var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    pickerEntry?.let { entry ->
        PlaylistPickerDialog(app = app, entry = entry, onDismiss = { pickerEntry = null })
    }

    // Playlists are local state; refresh is a quick visual confirmation.
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            delay(500)
            isRefreshing = false
        }
    }

    // Fall back to the Downloaded playlist if the selected one was deleted.
    LaunchedEffect(playlists, selectedId) {
        if (selectedId != DOWNLOADED_PLAYLIST_ID && playlists.none { it.id == selectedId }) {
            selectedId = DOWNLOADED_PLAYLIST_ID
        }
    }

    val addTarget = addingToPlaylist?.let { id -> playlists.firstOrNull { it.id == id } }
    if (addTarget != null) {
        AddEpisodesPane(
            app = app,
            playlistId = addTarget.id,
            playlistName = addTarget.name,
            onClose = { addingToPlaylist = null },
        )
        return
    }

    val selectedPlaylist = playlists.firstOrNull { it.id == selectedId }
    val rows: List<PlaylistEntry> = if (selectedId == DOWNLOADED_PLAYLIST_ID) {
        downloaded
            .sortedByDescending { it.downloadedAt }
            .map { PlaylistEntry(it.itemId, it.episodeId, it.title, it.podcastTitle) }
    } else {
        selectedPlaylist?.entries ?: emptyList()
    }

    // Playlist entries only store title/podcast, so look up publish date and
    // listening progress for each row (cached, so this is cheap after the first
    // load and degrades gracefully offline).
    val rowMeta by produceState(emptyMap<String, PlaylistRowMeta>(), rows, progressRevision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val info = mutableMapOf<String, EpisodeInfo>()
                rows.map { it.itemId }.distinct().forEach { id ->
                    runCatching {
                        app.repository.podcast(id).media.episodes.forEach { ep ->
                            info["$id:${ep.id}"] = EpisodeInfo(
                                publishedAt = ep.publishedAt,
                                pubDate = ep.pubDate,
                                durationSec = (ep.audioTrack?.duration ?: ep.audioFile?.duration ?: 0.0),
                            )
                        }
                    }
                }
                rows.associate { entry ->
                    val key = "${entry.itemId}:${entry.episodeId}"
                    val prog = runCatching {
                        app.repository.progress(entry.itemId, entry.episodeId)
                    }.getOrNull()
                    val ei = info[key]
                    key to PlaylistRowMeta(
                        fraction = (prog?.progress ?: 0.0).toFloat().coerceIn(0f, 1f),
                        isFinished = prog?.isFinished == true,
                        publishDate = formatEpisodeDate(ei?.publishedAt, ei?.pubDate),
                        durationSec = ei?.durationSec ?: 0.0,
                    )
                }
            }.getOrDefault(emptyMap())
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { isRefreshing = true },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            FilterChip(
                selected = selectedId == DOWNLOADED_PLAYLIST_ID,
                onClick = { selectedId = DOWNLOADED_PLAYLIST_ID },
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
            playlists.forEach { playlist ->
                FilterChip(
                    selected = selectedId == playlist.id,
                    onClick = { selectedId = playlist.id },
                    label = { Text(playlist.name) },
                )
            }
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New playlist")
            }
        }

        if (selectedPlaylist != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                TextButton(onClick = { addingToPlaylist = selectedPlaylist.id }) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add episodes")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { app.playlist.delete(selectedPlaylist.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete playlist",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (selectedId == DOWNLOADED_PLAYLIST_ID) {
                        "No downloaded episodes yet. Downloads appear here automatically for offline listening."
                    } else {
                        "This playlist is empty. Tap \"Add episodes\" to search your library, or use the playlist button on any episode."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Button(
                onClick = { controller?.playEntries(rows, 0) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Play all (${rows.size})")
            }
            if (selectedId != DOWNLOADED_PLAYLIST_ID) {
                OutlinedButton(
                    onClick = { bulkDownloadByIds(app, scope, rows.map { it.itemId to it.episodeId }) },
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Download all")
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { _, e -> "${e.itemId}:${e.episodeId}" }) { index, entry ->
                val meta = rowMeta["${entry.itemId}:${entry.episodeId}"]
                val isDownloaded = downloaded.any {
                    it.itemId == entry.itemId && it.episodeId == entry.episodeId
                }
                PlaylistRow(
                    entry = entry,
                    coverUrl = app.repository.coverUrl(entry.itemId),
                    isCurrent = playerState.mediaId == episodeMediaId(entry.itemId, entry.episodeId),
                    meta = meta,
                    downloadUi = downloadUiFor(app, activeDownloads, downloaded, entry.itemId, entry.episodeId),
                    removable = selectedId != DOWNLOADED_PLAYLIST_ID,
                    actions = EpisodeMenuActions(
                        isFinished = meta?.isFinished == true,
                        isDownloaded = isDownloaded,
                        onResetProgress = {
                            resetEpisodeProgress(app, scope, entry.itemId, entry.episodeId, meta?.durationSec ?: 0.0)
                        },
                        onToggleFinished = {
                            setEpisodeFinished(
                                app, scope, entry.itemId, entry.episodeId,
                                finished = meta?.isFinished != true,
                                durationSec = meta?.durationSec ?: 0.0,
                            )
                        },
                        onAddToPlaylist = { pickerEntry = entry },
                        onGoToPodcast = { onOpenPodcast(entry.itemId) },
                        onToggleDownload = {
                            toggleEpisodeDownload(app, scope, entry.itemId, entry.episodeId, isDownloaded)
                        },
                    ),
                    onClick = { controller?.playEntries(rows, index) },
                    onRemove = { app.playlist.removeFrom(selectedId, entry.itemId, entry.episodeId) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onCreate = { name ->
                selectedId = app.playlist.create(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun CreatePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog used from episode rows: pick which playlists an episode belongs to,
 * or create a new playlist and add it there directly.
 */
@Composable
fun PlaylistPickerDialog(app: ShelfieApp, entry: PlaylistEntry, onDismiss: () -> Unit) {
    val playlists by app.playlist.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                playlists.forEach { playlist ->
                    val contains = playlist.entries.any {
                        it.itemId == entry.itemId && it.episodeId == entry.episodeId
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { app.playlist.toggleIn(playlist.id, entry) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (contains) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "In playlist",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(0.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New playlist") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val id = app.playlist.create(newName)
                            app.playlist.addTo(id, entry)
                            newName = ""
                        },
                        enabled = newName.isNotBlank(),
                    ) {
                        Text("Create")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/** Picks a playlist and adds every one of [entries] to it (used by bulk select). */
@Composable
fun BulkPlaylistPickerDialog(
    app: ShelfieApp,
    entries: List<PlaylistEntry>,
    onDismiss: () -> Unit,
) {
    val playlists by app.playlist.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${entries.size} to playlist") },
        text = {
            Column {
                playlists.forEach { playlist ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                entries.forEach { app.playlist.addTo(playlist.id, it) }
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New playlist") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val id = app.playlist.create(newName)
                            entries.forEach { app.playlist.addTo(id, it) }
                            newName = ""
                            onDismiss()
                        },
                        enabled = newName.isNotBlank(),
                    ) {
                        Text("Create")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/** Search-and-add view shown when editing a playlist. */
@Composable
private fun AddEpisodesPane(
    app: ShelfieApp,
    playlistId: String,
    playlistName: String,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var episodeResults by remember {
        mutableStateOf<List<Pair<String, PlaylistEntry>>>(emptyList())
    }
    val playlists by app.playlist.playlists.collectAsState()
    val target = playlists.firstOrNull { it.id == playlistId }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            episodeResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400)
        val results = withContext(Dispatchers.IO) {
            runCatching {
                val (podcasts, episodes) = app.repository.search(query)
                val fromEpisodes = episodes.map { (podcast, episode) ->
                    app.repository.coverUrl(podcast.id) to PlaylistEntry(
                        itemId = podcast.id,
                        episodeId = episode.id,
                        title = episode.title ?: "Episode",
                        podcastTitle = podcast.media.metadata.title ?: "",
                    )
                }
                // For matching podcasts, offer their recent episodes too.
                val fromPodcasts = podcasts.take(3).flatMap { summary ->
                    runCatching {
                        val podcast = app.repository.podcast(summary.id)
                        podcast.media.episodes
                            .sortedByDescending { it.publishedAt ?: 0 }
                            .take(10)
                            .map { episode ->
                                app.repository.coverUrl(podcast.id) to PlaylistEntry(
                                    itemId = podcast.id,
                                    episodeId = episode.id,
                                    title = episode.title ?: "Episode",
                                    podcastTitle = podcast.media.metadata.title ?: "",
                                )
                            }
                    }.getOrDefault(emptyList())
                }
                (fromEpisodes + fromPodcasts).distinctBy { "${it.second.itemId}:${it.second.episodeId}" }
            }.getOrDefault(emptyList())
        }
        episodeResults = results
        searching = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Add to $playlistName",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search podcasts and episodes") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        when {
            searching -> {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                ) {
                    CircularProgressIndicator()
                }
            }

            episodeResults.isEmpty() && query.isNotBlank() -> {
                Text(
                    "No matches for \"$query\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(
                        episodeResults,
                        key = { "${it.second.itemId}:${it.second.episodeId}" },
                    ) { (coverUrl, entry) ->
                        val added = target?.entries?.any {
                            it.itemId == entry.itemId && it.episodeId == entry.episodeId
                        } == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { app.playlist.toggleIn(playlistId, entry) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            CoverImage(
                                model = coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
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
                            Icon(
                                if (added) Icons.Filled.PlaylistAddCheck else Icons.Filled.PlaylistAdd,
                                contentDescription = if (added) "Remove" else "Add",
                                tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
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
    meta: PlaylistRowMeta?,
    downloadUi: DownloadUi,
    removable: Boolean,
    actions: EpisodeMenuActions,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val fraction = meta?.fraction ?: 0f
    val completed = isNearlyComplete(fraction, meta?.isFinished == true)
    val dateLine = listOf(
        meta?.publishDate.orEmpty(),
        formatDuration((meta?.durationSec ?: 0.0).toLong()),
    )
        .filter { it.isNotBlank() }
        .joinToString(" • ")
    EpisodeLongPressBox(onClick = onClick, actions = actions, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            EpisodeRowContent(
                coverUrl = coverUrl,
                title = entry.title,
                subtitle = entry.podcastTitle,
                dateLine = dateLine,
                progressFraction = fraction,
                completed = completed,
                titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                downloadUi = downloadUi,
            )
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
}
