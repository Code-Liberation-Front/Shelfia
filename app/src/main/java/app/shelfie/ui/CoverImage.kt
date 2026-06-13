package app.shelfie.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.shelfie.R
import coil.compose.AsyncImage

/**
 * Cover artwork with a default book placeholder for items whose cover is
 * missing or fails to load (common for audiobooks/MP3s without artwork).
 *
 * When [completed] is true (the episode is finished or within 1% of the end)
 * the artwork is blurred and a checkmark is overlaid, so the caller can hide
 * the progress bar.
 */
@Composable
fun CoverImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    completed: Boolean = false,
) {
    val default = painterResource(R.drawable.ic_default_cover)
    if (!completed) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            placeholder = default,
            error = default,
            fallback = default,
            contentScale = contentScale,
            modifier = modifier,
        )
        return
    }

    // Blur is only supported on Android 12+; older devices keep the sharp image
    // under the scrim, which still reads as "finished".
    val artworkModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.fillMaxSize().blur(8.dp)
    } else {
        Modifier.fillMaxSize()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            placeholder = default,
            error = default,
            fallback = default,
            contentScale = contentScale,
            modifier = artworkModifier,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Finished",
            tint = Color.White,
            modifier = Modifier.fillMaxSize(0.45f),
        )
    }
}
