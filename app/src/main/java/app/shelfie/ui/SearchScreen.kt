package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ShelfieApp
import app.shelfie.data.LibraryItemExpanded
import app.shelfie.data.LibraryItemSummary
import app.shelfie.data.PodcastEpisode
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class SearchResults(
    val podcasts: List<LibraryItemSummary>,
    val episodes: List<Pair<LibraryItemExpanded, PodcastEpisode>>,
) {
    val isEmpty: Boolean get() = podcasts.isEmpty() && episodes.isEmpty()
}

@Composable
fun SearchScreen(
    app: ShelfieApp,
    controller: MediaController?,
    onOpenPodcast: (String) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val completedDownloads by app.downloads.completed.collectAsState()
    var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }

    pickerEntry?.let { entry ->
        PlaylistPickerDialog(app = app, entry = entry, onDismiss = { pickerEntry = null })
    }

    LaunchedEffect(query) {
        error = null
        if (query.isBlank()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400) // debounce typing
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val (podcasts, episodes) = app.repository.search(query)
                SearchResults(podcasts, episodes)
            }
        }
        outcome.fold(
            onSuccess = { results = it },
            onFailure = { error = it.message ?: "Search failed" },
        )
        searching = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search podcasts and episodes") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        when {
            searching -> {
                Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                ) {
                    CircularProgressIndicator()
                }
            }

            error != null -> {
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            results?.isEmpty == true -> {
                Text(
                    "No matches for \"$query\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            results != null -> {
                val found = results ?: return@Column
                LazyColumn(Modifier.fillMaxSize()) {
                    if (found.podcasts.isNotEmpty()) {
                        item { SearchSectionTitle("Podcasts") }
                        items(found.podcasts, key = { "p:${it.id}" }) { podcast ->
                            PodcastResultRow(
                                title = podcast.media.metadata.title ?: "Podcast",
                                author = podcast.media.metadata.author ?: "",
                                coverUrl = app.repository.coverUrl(podcast.id),
                                onClick = { onOpenPodcast(podcast.id) },
                            )
                        }
                    }
                    if (found.episodes.isNotEmpty()) {
                        item { SearchSectionTitle("Episodes") }
                        items(found.episodes, key = { "e:${it.second.id}" }) { (podcast, episode) ->
                            val itemId = podcast.id
                            val durationSec = (episode.audioTrack?.duration ?: episode.audioFile?.duration ?: 0.0)
                            val isDownloaded = completedDownloads.any {
                                it.itemId == itemId && it.episodeId == episode.id
                            }
                            EpisodeResultRow(
                                episode = episode,
                                podcastTitle = podcast.media.metadata.title ?: "",
                                coverUrl = app.repository.coverUrl(itemId),
                                actions = EpisodeMenuActions(
                                    isFinished = false,
                                    isDownloaded = isDownloaded,
                                    onResetProgress = {
                                        resetEpisodeProgress(app, scope, itemId, episode.id, durationSec)
                                    },
                                    onToggleFinished = {
                                        setEpisodeFinished(app, scope, itemId, episode.id, finished = true, durationSec = durationSec)
                                    },
                                    onAddToPlaylist = {
                                        pickerEntry = PlaylistEntry(
                                            itemId = itemId,
                                            episodeId = episode.id,
                                            title = episode.title ?: "Episode",
                                            podcastTitle = podcast.media.metadata.title ?: "",
                                        )
                                    },
                                    onGoToPodcast = { onOpenPodcast(itemId) },
                                    onToggleDownload = {
                                        toggleEpisodeDownload(app, scope, itemId, episode.id, isDownloaded)
                                    },
                                ),
                                onClick = { controller?.playEpisode(itemId, episode.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun PodcastResultRow(title: String, author: String, coverUrl: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CoverImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            MarqueeText(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            if (author.isNotBlank()) {
                Text(
                    author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EpisodeResultRow(
    episode: PodcastEpisode,
    podcastTitle: String,
    coverUrl: String,
    actions: EpisodeMenuActions,
    onClick: () -> Unit,
) {
    EpisodeLongPressBox(onClick = onClick, actions = actions, modifier = Modifier.fillMaxWidth()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CoverImage(
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
            MarqueeText(
                episode.title ?: "Episode",
                style = MaterialTheme.typography.titleSmall,
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
            val date = formatEpisodeDate(episode.publishedAt, episode.pubDate)
            if (date.isNotBlank()) {
                Text(
                    date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Filled.PlayCircle,
            contentDescription = "Play",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(30.dp),
        )
    }
    }
}
