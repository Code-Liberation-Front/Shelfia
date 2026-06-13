package app.shelfie.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import app.shelfie.playback.EXTRA_PUBLISHED_AT
import app.shelfie.playback.EXTRA_PUB_DATE
import kotlinx.coroutines.delay

@Stable
class PlayerUiState {
    var mediaId by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var artworkUri by mutableStateOf<Uri?>(null)
    var publishDate by mutableStateOf("")
    var isPlaying by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var speed by mutableStateOf(1f)

    val hasMedia: Boolean get() = mediaId != null
}

@Composable
fun rememberPlayerUiState(controller: MediaController?): PlayerUiState {
    val state = remember(controller) { PlayerUiState() }

    DisposableEffect(controller) {
        if (controller == null) {
            return@DisposableEffect onDispose { }
        }

        fun sync() {
            state.mediaId = controller.currentMediaItem?.mediaId
            state.title = controller.mediaMetadata.title?.toString() ?: ""
            state.artist = controller.mediaMetadata.artist?.toString() ?: ""
            state.artworkUri = controller.mediaMetadata.artworkUri
            val extras = controller.mediaMetadata.extras
            val publishedAt = extras?.getLong(EXTRA_PUBLISHED_AT, 0L)?.takeIf { it > 0L }
            state.publishDate = formatEpisodeDate(publishedAt, extras?.getString(EXTRA_PUB_DATE))
            state.isPlaying = controller.isPlaying
            state.isLoading = controller.playbackState == Player.STATE_BUFFERING
            state.positionMs = controller.currentPosition.coerceAtLeast(0L)
            state.durationMs = controller.duration.coerceAtLeast(0L)
            state.speed = controller.playbackParameters.speed
        }
        sync()

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    LaunchedEffect(controller, state.isPlaying) {
        while (controller != null && state.isPlaying) {
            state.positionMs = controller.currentPosition.coerceAtLeast(0L)
            state.durationMs = controller.duration.coerceAtLeast(0L)
            delay(500)
        }
    }
    return state
}
