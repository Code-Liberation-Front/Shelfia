package app.shelfie.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private var latestCache: List<PodcastEpisode> = emptyList()

    @Volatile
    private var librariesCache: List<Library> = emptyList()

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
     * Begins the OIDC flow following Audiobookshelf's mobile contract: the app
     * itself requests /auth/openid (without following the redirect), captures the
     * state cookies plus the identity-provider URL, and returns that URL to open
     * in the browser. The cookies must be replayed on /auth/openid/callback.
     */
    suspend fun startOidcLogin(serverInput: String): String {
        val server = normalizeServerUrl(serverInput)
        val verifier = generateCodeVerifier()
        val challenge = codeChallenge(verifier)
        val redirect = URLEncoder.encode(OIDC_REDIRECT_URI, "UTF-8")
        val authUrl = "$server/auth/openid?response_type=code&client_id=Shelfie" +
            "&redirect_uri=$redirect&code_challenge=$challenge&code_challenge_method=S256"
        val (idpUrl, cookies) = withContext(Dispatchers.IO) { fetchOidcRedirect(server, authUrl) }
        settings.savePendingOidc(server, verifier, cookies)
        return idpUrl
    }

    private fun fetchOidcRedirect(server: String, authUrl: String): Pair<String, String> {
        val client = OkHttpClient.Builder().followRedirects(false).build()
        client.newCall(Request.Builder().url(authUrl).build()).execute().use { response ->
            if (response.code !in 300..399) {
                val body = runCatching { response.body?.string() }.getOrNull()
                    ?.take(200)?.trim().orEmpty()
                val detail = if (body.isNotBlank()) ": $body" else ""
                throw IllegalStateException(
                    "Server did not start the sign-in flow (HTTP ${response.code}$detail)",
                )
            }
            val location = response.header("Location")
                ?: throw IllegalStateException("Server did not return a sign-in redirect")
            val resolved = when {
                location.startsWith("http://") || location.startsWith("https://") -> location
                location.startsWith("/") -> "$server$location"
                else -> "$server/$location"
            }
            val cookies = response.headers("Set-Cookie")
                .joinToString("; ") { it.substringBefore(';') }
            return resolved to cookies
        }
    }

    /** Completes the OIDC flow with the code/state delivered via the deep link. */
    suspend fun completeOidcLogin(code: String, state: String) {
        val pending = settings.pendingOidc()
            ?: throw IllegalStateException("No sign-in attempt in progress. Please start over.")
        val (server, verifier, cookies) = pending
        val response = buildApi(server, token = null)
            .oidcCallback(code, state, verifier, cookies.ifBlank { null })
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
        latestCache = emptyList()
        librariesCache = emptyList()
        progressCache = emptyMap()
        settings.clear()
    }

    /** All libraries on the server (podcast, audiobook, music, …). */
    suspend fun libraries(forceRefresh: Boolean = false): List<Library> {
        if (!forceRefresh && librariesCache.isNotEmpty()) return librariesCache
        val result = try {
            requireApi().libraries().libraries.also { diskCacheWrite("libraries.json", it) }
        } catch (e: Exception) {
            diskCacheRead<List<Library>>("libraries.json") ?: throw e
        }
        librariesCache = result
        return result
    }

    /** Returns the active library id, defaulting to the first podcast library (else first library). */
    suspend fun activeLibraryId(): String {
        val saved = settings.snapshot().libraryId
        if (saved.isNotBlank()) return saved
        val all = libraries()
        val pick = all.firstOrNull { it.mediaType == "podcast" } ?: all.firstOrNull()
            ?: throw IllegalStateException("No libraries found on this server")
        settings.saveLibraryId(pick.id)
        return pick.id
    }

    suspend fun activeLibrary(): Library? {
        val id = activeLibraryId()
        return libraries().firstOrNull { it.id == id }
    }

    /** Switches the active library and clears caches so the next loads use it. */
    suspend fun selectLibrary(libraryId: String) {
        settings.saveLibraryId(libraryId)
        podcastsCache = emptyList()
        latestCache = emptyList()
        itemCache.clear()
        progressFetchedAt = 0
        runCatching {
            cacheDir?.listFiles()
                ?.filterNot { it.name == "libraries.json" }
                ?.forEach { it.delete() }
        }
    }

    suspend fun podcasts(forceRefresh: Boolean = false): List<LibraryItemSummary> {
        if (!forceRefresh && podcastsCache.isNotEmpty()) return podcastsCache
        val items = try {
            val libraryId = activeLibraryId()
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
                    .associateBy { "${it.libraryItemId}:${it.episodeId ?: ""}" }
                progressFetchedAt = now
                diskCacheWrite("progress.json", progressCache.values.toList())
            } catch (e: Exception) {
                if (progressCache.isEmpty()) {
                    progressCache = diskCacheRead<List<MediaProgress>>("progress.json")
                        ?.associateBy { "${it.libraryItemId}:${it.episodeId ?: ""}" }
                        ?: emptyMap()
                }
                // Offline: serve the last known progress instead of failing.
            }
        }
        return progressCache
    }

    suspend fun progress(itemId: String, episodeId: String, maxAgeMs: Long = 30_000): MediaProgress? =
        progressMap(maxAgeMs)["$itemId:$episodeId"]

    /** Whole-book progress for audiobook/music library items. */
    suspend fun bookProgress(itemId: String, maxAgeMs: Long = 30_000): MediaProgress? =
        progressMap(maxAgeMs)["$itemId:"]

    data class InProgressEpisode(
        val podcast: LibraryItemExpanded,
        val episode: PodcastEpisode,
        val progress: Double,
    )

    /** Episodes the user has started but not finished, most recently played first. */
    suspend fun continueListening(limit: Int = 15, forceRefresh: Boolean = false): List<InProgressEpisode> =
        progressMap(maxAgeMs = if (forceRefresh) 0 else 30_000).values
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
    suspend fun recentlyAdded(limit: Int = 12, forceRefresh: Boolean = false): List<LibraryItemSummary> =
        podcasts(forceRefresh).sortedByDescending { it.addedAt }.take(limit)

    /** Newest episodes across the whole library, latest first. */
    suspend fun latestEpisodes(limit: Int = 75, forceRefresh: Boolean = false): List<PodcastEpisode> {
        if (!forceRefresh && latestCache.isNotEmpty()) return latestCache
        val active = runCatching { activeLibrary() }.getOrNull()
        if (active != null && active.mediaType != "podcast") {
            throw IllegalStateException("Latest episodes are only available for podcast libraries")
        }
        val result = try {
            requireApi().recentEpisodes(activeLibraryId(), limit).episodes
                .sortedByDescending { it.publishedAt ?: 0 }
                .also { diskCacheWrite("latest.json", it) }
        } catch (e: Exception) {
            diskCacheRead<List<PodcastEpisode>>("latest.json") ?: throw e
        }
        latestCache = result
        return result
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
        val body = ProgressUpdate(
            currentTime = currentTimeSec,
            duration = durationSec,
            progress = progress,
            isFinished = progress > 0.98,
        )
        if (episodeId.isBlank()) {
            requireApi().updateBookProgress(itemId, body)
        } else {
            requireApi().updateEpisodeProgress(itemId, episodeId, body)
        }
    }

    fun coverUrl(itemId: String): String = "$serverUrl/api/items/$itemId/cover?token=$token"

    fun streamUrl(itemId: String, episode: PodcastEpisode): String? {
        val contentUrl = episode.audioTrack?.contentUrl
            ?: episode.audioFile?.ino?.takeIf { it.isNotBlank() }?.let { "/api/items/$itemId/file/$it" }
            ?: return null
        return tokenizedUrl(contentUrl)
    }

    /** Appends the auth token to a server-relative content URL. */
    fun tokenizedUrl(contentUrl: String): String {
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
