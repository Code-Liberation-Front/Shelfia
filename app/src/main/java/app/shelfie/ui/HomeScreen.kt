package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import app.shelfie.data.AbsRepository
import app.shelfie.data.LibraryItemSummary
import app.shelfie.playlist.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface HomeUi {
    data object Loading : HomeUi
    data class Error(val message: String) : HomeUi
    data class Ready(
        val inProgress: List<AbsRepository.InProgressEpisode>,
        val recentlyAdded: List<LibraryItemSummary>,
    ) : HomeUi
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: ShelfieApp,
    controller: MediaController?,
    onOpenPodcast: (String) -> Unit,
) {
    val progressRevision by app.repository.progressRevision.collectAsState()
    val contentVersion by app.repository.contentVersion.collectAsState()
    val refreshing by app.repository.refreshing.collectAsState()
    val scope = rememberCoroutineScope()
    // Read from cache (fast); the global refresh updates the cache and bumps
    // contentVersion, which reloads this from the fresh data.
    val ui by produceState<HomeUi>(initialValue = HomeUi.Loading, contentVersion, progressRevision) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!app.repository.ensureConfigured()) {
                    HomeUi.Error("Not logged in")
                } else {
                    HomeUi.Ready(
                        inProgress = app.repository.continueListening(limit = 12),
                        recentlyAdded = app.repository.recentlyAdded(),
                    )
                }
            } catch (e: Exception) {
                HomeUi.Error(e.message ?: "Failed to load home")
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { app.repository.refreshAll() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        HomeContent(app, controller, onOpenPodcast, ui)
    }
}

@Composable
private fun HomeContent(
    app: ShelfieApp,
    controller: MediaController?,
    onOpenPodcast: (String) -> Unit,
    ui: HomeUi,
) {
    when (val state = ui) {
        is HomeUi.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is HomeUi.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }

        is HomeUi.Ready -> {
            val scope = rememberCoroutineScope()
            val completedDownloads by app.downloads.completed.collectAsState()
            var pickerEntry by remember { mutableStateOf<PlaylistEntry?>(null) }

            pickerEntry?.let { entry ->
                PlaylistPickerDialog(app = app, entry = entry, onDismiss = { pickerEntry = null })
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                item { SectionTitle("Continue Listening") }
                item {
                    if (state.inProgress.isEmpty()) {
                        EmptyHint("Nothing in progress yet — pick an episode and start listening.")
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.inProgress, key = { it.episode.id }) { entry ->
                                val itemId = entry.podcast.id
                                val episodeId = entry.episode.id
                                val durationSec = (entry.episode.audioTrack?.duration
                                    ?: entry.episode.audioFile?.duration ?: 0.0)
                                val isDownloaded = completedDownloads.any {
                                    it.itemId == itemId && it.episodeId == episodeId
                                }
                                ContinueCard(
                                    entry = entry,
                                    coverUrl = app.repository.coverUrl(itemId),
                                    actions = EpisodeMenuActions(
                                        isFinished = false,
                                        isDownloaded = isDownloaded,
                                        onResetProgress = {
                                            resetEpisodeProgress(app, scope, itemId, episodeId, durationSec)
                                        },
                                        onToggleFinished = {
                                            setEpisodeFinished(app, scope, itemId, episodeId, finished = true, durationSec = durationSec)
                                        },
                                        onAddToPlaylist = {
                                            pickerEntry = PlaylistEntry(
                                                itemId = itemId,
                                                episodeId = episodeId,
                                                title = entry.episode.title ?: "Episode",
                                                podcastTitle = entry.podcast.media.metadata.title ?: "",
                                            )
                                        },
                                        onGoToPodcast = { onOpenPodcast(itemId) },
                                        onToggleDownload = {
                                            toggleEpisodeDownload(app, scope, itemId, episodeId, isDownloaded)
                                        },
                                    ),
                                    onClick = { controller?.playEpisode(itemId, episodeId) },
                                )
                            }
                        }
                    }
                }
                item { SectionTitle("Recently Added") }
                item {
                    if (state.recentlyAdded.isEmpty()) {
                        EmptyHint("No podcasts in this library yet.")
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.recentlyAdded, key = { it.id }) { podcast ->
                                RecentPodcastCard(
                                    podcast = podcast,
                                    coverUrl = app.repository.coverUrl(podcast.id),
                                    onClick = { onOpenPodcast(podcast.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ContinueCard(
    entry: AbsRepository.InProgressEpisode,
    coverUrl: String,
    actions: EpisodeMenuActions,
    onClick: () -> Unit,
) {
    val completed = isNearlyComplete(entry.progress.toFloat(), isFinished = false)
    EpisodeLongPressBox(onClick = onClick, actions = actions, modifier = Modifier.width(150.dp)) {
    Column(
        modifier = Modifier.width(150.dp),
    ) {
        CoverImage(
            model = coverUrl,
            contentDescription = entry.episode.title,
            contentScale = ContentScale.Crop,
            completed = completed,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp)),
        )
        if (!completed) {
            LinearProgressIndicator(
                progress = { entry.progress.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(3.dp),
            )
        }
        Text(
            entry.episode.title ?: "Episode",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        Text(
            entry.podcast.media.metadata.title ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val date = formatEpisodeDate(entry.episode.publishedAt, entry.episode.pubDate)
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
    }
}

@Composable
private fun RecentPodcastCard(
    podcast: LibraryItemSummary,
    coverUrl: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onClick),
    ) {
        CoverImage(
            model = coverUrl,
            contentDescription = podcast.media.metadata.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp)),
        )
        Text(
            podcast.media.metadata.title ?: "Podcast",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
