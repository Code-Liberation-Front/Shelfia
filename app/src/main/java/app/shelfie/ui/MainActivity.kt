package app.shelfie.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.shelfie.ShelfieApp
import app.shelfie.playback.PlaybackService
import app.shelfie.ui.theme.ShelfieTheme
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)
    private val loginError = mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        val app = application as ShelfieApp
        setContent {
            ShelfieTheme {
                val controller by controllerState
                val error by loginError
                ShelfieRoot(app = app, controller = controller, loginError = error)
            }
        }
        handleOidcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOidcIntent(intent)
    }

    /** Finishes the OIDC login when the browser redirects back to audiobookshelf://oauth. */
    private fun handleOidcIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "audiobookshelf" || data.host != "oauth") return
        val code = data.getQueryParameter("code")
        val state = data.getQueryParameter("state")
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            loginError.value = "Sign-in was cancelled or the server returned no code."
            return
        }
        loginError.value = null
        val app = application as ShelfieApp
        lifecycleScope.launch {
            try {
                app.repository.completeOidcLogin(code, state)
            } catch (e: Exception) {
                loginError.value = e.message ?: "OIDC sign-in failed"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (future.isDone && !future.isCancelled) {
                    runCatching { controllerState.value = future.get() }
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onStop() {
        controllerState.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun ShelfieRoot(app: ShelfieApp, controller: MediaController?, loginError: String? = null) {
    val credentials by app.settings.credentials.collectAsState(initial = null)
    val creds = credentials

    when {
        creds == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        !creds.isLoggedIn -> LoginScreen(app, externalError = loginError)

        else -> MainNavigation(app, controller)
    }
}

@Composable
fun MainNavigation(app: ShelfieApp, controller: MediaController?) {
    val navController = rememberNavController()
    val playerState = rememberPlayerUiState(controller)

    Scaffold(
        bottomBar = {
            NowPlayingBar(
                state = playerState,
                controller = controller,
                onExpand = {
                    navController.navigate("player") { launchSingleTop = true }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "podcasts",
            modifier = Modifier.padding(padding),
        ) {
            composable("podcasts") {
                PodcastsScreen(
                    app = app,
                    onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                )
            }
            composable("podcast/{itemId}") { backStackEntry ->
                val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
                EpisodesScreen(
                    app = app,
                    itemId = itemId,
                    controller = controller,
                    playerState = playerState,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("player") {
                PlayerScreen(
                    state = playerState,
                    controller = controller,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
