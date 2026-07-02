package app.shelfie.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.shelfie.R
import app.shelfie.ShelfieApp
import app.shelfie.playback.PlaybackService
import app.shelfie.ui.theme.ShelfieTheme
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

// AppCompatActivity (a FragmentActivity) is required for the Cast chooser dialog.
class MainActivity : AppCompatActivity() {

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
                loginError.value = if (e is retrofit2.HttpException && e.code() == 400) {
                    val serverMessage = runCatching {
                        e.response()?.errorBody()?.string()?.take(200)?.trim()
                    }.getOrNull().orEmpty()
                    val detail = if (serverMessage.isNotBlank()) " — $serverMessage" else ""
                    "Sign-in rejected by the server (HTTP 400$detail). If this mentions the " +
                        "redirect URI, add audiobookshelf://oauth to \"Allowed Mobile Redirect " +
                        "URIs\" in Audiobookshelf Settings → Authentication."
                } else {
                    e.message ?: "OIDC sign-in failed"
                }
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

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val BOTTOM_TABS = listOf(
    BottomTab("home", "Home", Icons.Filled.Home),
    BottomTab("latest", "Latest", Icons.Filled.Schedule),
    BottomTab("library", "Library", Icons.Filled.GridView),
    BottomTab("playlist", "Playlist", Icons.Filled.PlaylistPlay),
)

@Composable
fun MainNavigation(app: ShelfieApp, controller: MediaController?) {
    val navController = rememberNavController()
    val playerState = rememberPlayerUiState(controller)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onTabScreen = BOTTOM_TABS.any { it.route == currentRoute }
    val isOnline by rememberIsOnline()
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    val refreshing by app.repository.refreshing.collectAsState()
    val scope = rememberCoroutineScope()

    // Refresh all content once on launch; caches serve the UI instantly and the
    // differences are updated when this completes.
    LaunchedEffect(Unit) { app.repository.refreshAll() }

    BackHandler(enabled = playerExpanded) { playerExpanded = false }

    Box(Modifier.fillMaxSize()) {
        MainScaffold(
            app = app,
            controller = controller,
            navController = navController,
            playerState = playerState,
            onTabScreen = onTabScreen,
            currentRoute = currentRoute,
            isOnline = isOnline,
            refreshing = refreshing,
            onRefresh = { scope.launch { app.repository.refreshAll() } },
            onExpandPlayer = { playerExpanded = true },
        )
        // Apple Music-style full-screen player: slides up over everything,
        // including the now-playing bar and bottom navigation.
        AnimatedVisibility(
            visible = playerExpanded,
            enter = slideInVertically(animationSpec = tween(300), initialOffsetY = { it }),
            exit = slideOutVertically(animationSpec = tween(300), targetOffsetY = { it }),
        ) {
            PlayerScreen(
                state = playerState,
                controller = controller,
                onBack = { playerExpanded = false },
            )
        }
    }
}

@Composable
private fun MainScaffold(
    app: ShelfieApp,
    controller: MediaController?,
    navController: androidx.navigation.NavHostController,
    playerState: PlayerUiState,
    onTabScreen: Boolean,
    currentRoute: String?,
    isOnline: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onExpandPlayer: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                if (onTabScreen) {
                    ShelfieTopBar(
                        onSearch = { navController.navigate("search") { launchSingleTop = true } },
                        onRefresh = onRefresh,
                        onSettings = { navController.navigate("settings") { launchSingleTop = true } },
                    )
                }
                if (!isOnline) {
                    OfflineBanner(padStatusBar = !onTabScreen)
                }
                // App-wide loading bar that scrolls across the top while content
                // is being refreshed (on launch or a manual update).
                if (refreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            // The NavigationBar consumes the gesture-nav inset itself; when it's
            // hidden the now-playing bar must avoid the navigation bar on its own.
            Column(if (onTabScreen) Modifier else Modifier.navigationBarsPadding()) {
                NowPlayingBar(
                    state = playerState,
                    controller = controller,
                    onExpand = onExpandPlayer,
                )
                if (onTabScreen) {
                    NavigationBar {
                        BOTTOM_TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // Non-tab screens have no top bar, and a zero-height topBar slot means the
        // Scaffold applies no status-bar inset — pad explicitly. When offline the
        // banner occupies the slot (with its own inset), so skip it then.
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .padding(padding)
                .then(if (!onTabScreen && isOnline) Modifier.statusBarsPadding() else Modifier),
        ) {
            composable("home") {
                if (!isOnline) {
                    OfflineTabHint()
                } else {
                    HomeScreen(
                        app = app,
                        controller = controller,
                        onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                    )
                }
            }
            composable("latest") {
                if (!isOnline) {
                    OfflineTabHint()
                } else {
                    LatestScreen(
                        app = app,
                        controller = controller,
                        playerState = playerState,
                        onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                    )
                }
            }
            composable("library") {
                if (!isOnline) {
                    OfflineTabHint()
                } else {
                    PodcastsScreen(
                        app = app,
                        onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                    )
                }
            }
            composable("podcast/{itemId}") { entry ->
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                EpisodesScreen(
                    app = app,
                    itemId = itemId,
                    controller = controller,
                    playerState = playerState,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("playlist") {
                PlaylistScreen(
                    app = app,
                    controller = controller,
                    playerState = playerState,
                    onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                )
            }
            composable("search") {
                SearchScreen(
                    app = app,
                    controller = controller,
                    onOpenPodcast = { itemId -> navController.navigate("podcast/$itemId") },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    app = app,
                    onOpenDownloads = { navController.navigate("downloads") { launchSingleTop = true } },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    app = app,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun ShelfieTopBar(onSearch: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Shelfie",
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            "Shelfie",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search")
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
        }
        CastButton(modifier = Modifier.size(44.dp))
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}
