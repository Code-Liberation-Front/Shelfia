package app.shelfie.data

import android.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point to the Audiobookshelf server. Holds the configured
 * Retrofit client plus small in-memory caches shared by the UI and the
 * playback service.
 */
class AbsRepository(
    private val settings: SettingsStore,
    /** Directory for offline JSON caches; falls back to network-only when null. */
    private val cacheDir: File? = null,
) {

    init {
        cacheDir?.mkdirs()
    }

    @Volatile
    var serverUrl: String = ""
        private set

    @Volatile
    var token: String = ""
        private set

    @Volatile
    private var api: AbsApi? = null

    private val itemCache = ConcurrentHashMap<String, LibraryItemExpanded>()

    @Volatile
    private var podcastsCache: List<LibraryItemSummary> = emptyList()

    @Volatile
    private var progressCache: Map<String, MediaProgress> = emptyMap()

    @Volatile
    private var progressFetchedAt: Long = 0

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    fun configure(serverUrl: String, token: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.token = token
        api = buildApi(this.serverUrl, token)
    }

    /** Restores the client from persisted credentials. Returns false if not logged in. */
    suspend fun ensureConfigured(): Boolean {
        if (api != null) return true
        val creds = settings.snapshot()
        if (!creds.isLoggedIn) return false
        configure(creds.serverUrl, creds.token)
        return true
    }

    /** Fetches the server's enabled auth methods before any credentials exist. */
    suspend fun serverStatus(serverInput: String): ServerStatus {
        val server = normalizeServerUrl(serverInput)
        return buildApi(server, token = null).status()
    }

    /**
     * Begins the OIDC flow: persists a PKCE verifier for the browser round-trip and
     * returns the authorization URL to open. Audiobookshelf redirects the browser back
     * to audiobookshelf://oauth?code=...&state=... once the identity provider finishes.
     */
    suspend fun startOidcLogin(serverInput: String): String {
        val server = normalizeServerUrl(serverInput)
        val verifier = generateCodeVerifier()
        settings.savePendingOidc(server, verifier)
        val challenge = codeChallenge(verifier)
        val redirect = URLEncoder.encode(OIDC_REDIRECT_URI, "UTF-8")
        return "$server/auth/openid?response_type=code&client_id=Shelfie" +
            "&redirect_uri=$redirect&code_challenge=$challenge&code_challenge_method=S256"
    }

    /** Completes the OIDC flow with the code/state delivered via the deep link. */
    suspend fun completeOidcLogin(code: String, state: String) {
        val (server, verifier) = settings.pendingOidc()
            ?: throw IllegalStateException("No sign-in attempt in progress. Please start over.")
        val response = buildApi(server, token = null).oidcCallback(code, state, verifier)
        configure(server, response.user.token)
        settings.saveLogin(server, response.user.token, response.user.id, response.user.username)
        settings.clearPendingOidc()
    }

    suspend fun login(serverInput: String, username: String, password: String) {
        val server = normalizeServerUrl(serverInput)
        val anonymous = buildApi(server, token = null)
        val response = anonymous.login(LoginRequest(username, password))
        configure(server, response.user.token)
        settings.saveLogin(server, response.user.token, response.user.id, response.user.username)
    }

    suspend fun logout() {
        api = null
        serverUrl = ""
        token = ""
        itemCache.clear()
        podcastsCache = emptyList()
        progressCache = emptyMap()
        settings.clear()
    }

    /** Returns the podcast library id, picking and persisting the first podcast library if unset. */
    suspend fun podcastLibraryId(): String {
        val saved = settings.snapshot().libraryId
        if (saved.isNotBlank()) return saved
        val libraries = requireApi().libraries().libraries
        val podcastLibrary = libraries.firstOrNull { it.mediaType == "podcast" }
            ?: throw IllegalStateException("No podcast library found on this server")
        settings.saveLibraryId(podcastLibrary.id)
        return podcastLibrary.id
    }

    suspend fun podcasts(forceRefresh: Boolean = false): List<LibraryItemSummary> {
        if (!forceRefresh && podcastsCache.isNotEmpty()) return podcastsCache
        val items = try {
            val libraryId = podcastLibraryId()
            requireApi().libraryItems(libraryId).results.also {
                diskCacheWrite("podcasts.json", it)
            }
        } catch (e: Exception) {
            diskCacheRead<List<LibraryItemSummary>>("podcasts.json") ?: throw e
        }
        podcastsCache = items
        return items
    }

    suspend fun podcast(itemId: String, forceRefresh: Boolean = false): LibraryItemExpanded {
        if (!forceRefresh) itemCache[itemId]?.let { return it }
        val item = try {
            requireApi().item(itemId).also { diskCacheWrite("item_$itemId.json", it) }
        } catch (e: Exception) {
            diskCacheRead<LibraryItemExpanded>("item_$itemId.json") ?: throw e
        }
        itemCache[itemId] = item
        return item
    }

    /** Server-side listening progress, cached briefly to avoid hammering /api/me. */
    private suspend fun progressMap(maxAgeMs: Long = 30_000): Map<String, MediaProgress> {
        val now = System.currentTimeMillis()
        if (now - progressFetchedAt > maxAgeMs) {
            try {
                val me = requireApi().me()
                progressCache = me.mediaProgress
                    .filter { it.episodeId != null }
                    .associateBy { "${it.libraryItemId}:${it.episodeId}" }
                progressFetchedAt = now
                diskCacheWrite("progress.json", progressCache.values.toList())
            } catch (e: Exception) {
                if (progressCache.isEmpty()) {
                    progressCache = diskCacheRead<List<MediaProgress>>("progress.json")
                        ?.associateBy { "${it.libraryItemId}:${it.episodeId}" }
                        ?: emptyMap()
                }
                // Offline: serve the last known progress instead of failing.
            }
        }
        return progressCache
    }

    suspend fun progress(itemId: String, episodeId: String, maxAgeMs: Long = 30_000): MediaProgress? =
        progressMap(maxAgeMs)["$itemId:$episodeId"]

    data class InProgressEpisode(
        val podcast: LibraryItemExpanded,
        val episode: PodcastEpisode,
        val progress: Double,
    )

    /** Episodes the user has started but not finished, most recently played first. */
    suspend fun continueListening(limit: Int = 15): List<InProgressEpisode> =
        progressMap().values
            .filter { it.episodeId != null && !it.isFinished && it.currentTime > 0 }
            .sortedByDescending { it.lastUpdate }
            .take(limit)
            .mapNotNull { mp ->
                val podcast = runCatching { podcast(mp.libraryItemId) }.getOrNull()
                    ?: return@mapNotNull null
                val episode = podcast.media.episodes.firstOrNull { it.id == mp.episodeId }
                    ?: return@mapNotNull null
                InProgressEpisode(podcast, episode, mp.progress.coerceIn(0.0, 1.0))
            }

    /** Most recently added podcasts in the library. */
    suspend fun recentlyAdded(limit: Int = 12): List<LibraryItemSummary> =
        podcasts().sortedByDescending { it.addedAt }.take(limit)

    /** Newest episodes across the whole library, latest first. */
    suspend fun latestEpisodes(limit: Int = 75): List<PodcastEpisode> = try {
        requireApi().recentEpisodes(podcastLibraryId(), limit).episodes
            .sortedByDescending { it.publishedAt ?: 0 }
            .also { diskCacheWrite("latest.json", it) }
    } catch (e: Exception) {
        diskCacheRead<List<PodcastEpisode>>("latest.json") ?: throw e
    }

    suspend fun listeningStats(): ListeningStats = requireApi().listeningStats()

    /**
     * Title/author search across podcasts plus episode-title search, used by
     * Android Auto browse search and voice queries.
     */
    suspend fun search(
        query: String,
        maxPodcastFetches: Int = 20,
    ): Pair<List<LibraryItemSummary>, List<Pair<LibraryItemExpanded, PodcastEpisode>>> {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList<LibraryItemSummary>() to emptyList()
        val all = podcasts()
        val podcastMatches = all.filter {
            it.media.metadata.title.orEmpty().lowercase().contains(needle) ||
                it.media.metadata.author.orEmpty().lowercase().contains(needle)
        }
        val episodeMatches = mutableListOf<Pair<LibraryItemExpanded, PodcastEpisode>>()
        for (summary in all.take(maxPodcastFetches)) {
            val podcast = runCatching { podcast(summary.id) }.getOrNull() ?: continue
            podcast.media.episodes
                .filter { it.title.orEmpty().lowercase().contains(needle) }
                .sortedByDescending { it.publishedAt ?: 0 }
                .take(5)
                .forEach { episodeMatches.add(podcast to it) }
            if (episodeMatches.size >= 25) break
        }
        return podcastMatches to episodeMatches
    }

    suspend fun updateProgress(itemId: String, episodeId: String, currentTimeSec: Double, durationSec: Double) {
        val progress = if (durationSec > 0) (currentTimeSec / durationSec).coerceIn(0.0, 1.0) else 0.0
        requireApi().updateEpisodeProgress(
            itemId,
            episodeId,
            ProgressUpdate(
                currentTime = currentTimeSec,
                duration = durationSec,
                progress = progress,
                isFinished = progress > 0.98,
            ),
        )
    }

    fun coverUrl(itemId: String): String = "$serverUrl/api/items/$itemId/cover?token=$token"

    fun streamUrl(itemId: String, episode: PodcastEpisode): String? {
        val contentUrl = episode.audioTrack?.contentUrl
            ?: episode.audioFile?.ino?.takeIf { it.isNotBlank() }?.let { "/api/items/$itemId/file/$it" }
            ?: return null
        val separator = if (contentUrl.contains('?')) "&" else "?"
        return "$serverUrl$contentUrl${separator}token=$token"
    }

    private fun requireApi(): AbsApi =
        api ?: throw IllegalStateException("Not logged in")

    private inline fun <reified T> diskCacheWrite(name: String, value: T) {
        runCatching { cacheDir?.resolve(name)?.writeText(json.encodeToString(value)) }
    }

    private inline fun <reified T> diskCacheRead(name: String): T? = runCatching {
        cacheDir?.resolve(name)?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<T>(it) }
    }.getOrNull()

    private fun buildApi(serverUrl: String, token: String?): AbsApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("${serverUrl.trimEnd('/')}/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AbsApi::class.java)
    }

    companion object {
        const val OIDC_REDIRECT_URI = "audiobookshelf://oauth"

        private fun generateCodeVerifier(): String {
            val bytes = ByteArray(64)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        private fun codeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        fun normalizeServerUrl(input: String): String {
            var url = input.trim().trimEnd('/')
            if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            return url
        }
    }
}
