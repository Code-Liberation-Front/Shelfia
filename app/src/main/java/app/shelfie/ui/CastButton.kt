package app.shelfie.ui

import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import app.shelfie.R
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext

/**
 * Chromecast device picker. Renders nothing on devices without Google Play
 * services (where the Cast framework cannot initialize).
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val castAvailable = remember {
        runCatching { CastContext.getSharedInstance(context.applicationContext) }.isSuccess
    }
    if (!castAvailable) return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val themed = ContextThemeWrapper(ctx, R.style.Theme_Shelfie_Cast)
            MediaRouteButton(themed).also { button ->
                CastButtonFactory.setUpMediaRouteButton(themed, button)
            }
        },
    )
}
