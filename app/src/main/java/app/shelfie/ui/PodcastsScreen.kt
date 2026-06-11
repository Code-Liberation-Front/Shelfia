package app.shelfie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shelfie.ShelfieApp
import app.shelfie.data.LibraryItemSummary
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface PodcastsUi {
    data object Loading : PodcastsUi
    data class Error(val message: String) : PodcastsUi
    data class Ready(val podcasts: List<LibraryItemSummary>) : PodcastsUi
}

@Composable
fun PodcastsScreen(app: ShelfieApp, onOpenPodcast: (String) -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val ui by produceState<PodcastsUi>(initialValue = PodcastsUi.Loading, refreshKey) {
        value = PodcastsUi.Loading
        value = withContext(Dispatchers.IO) {
            try {
                if (!app.repository.ensureConfigured()) {
                    PodcastsUi.Error("Not logged in")
                } else {
                    PodcastsUi.Ready(app.repository.podcasts(forceRefresh = refreshKey > 0))
                }
            } catch (e: Exception) {
                PodcastsUi.Error(e.message ?: "Failed to load podcasts")
            }
        }
    }

    when (val state = ui) {
        is PodcastsUi.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is PodcastsUi.Error -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { refreshKey++ }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Retry")
                }
            }
        }

        is PodcastsUi.Ready -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.podcasts, key = { it.id }) { podcast ->
                    PodcastCard(
                        podcast = podcast,
                        coverUrl = app.repository.coverUrl(podcast.id),
                        onClick = { onOpenPodcast(podcast.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PodcastCard(podcast: LibraryItemSummary, coverUrl: String, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = coverUrl,
            contentDescription = podcast.media.metadata.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp)),
        )
        Text(
            text = podcast.media.metadata.title ?: "Podcast",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (podcast.media.numEpisodes > 0) {
            Text(
                text = "${podcast.media.numEpisodes} episodes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
