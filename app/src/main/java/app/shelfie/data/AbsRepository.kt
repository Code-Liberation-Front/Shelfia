package app.shelfie.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point to the Audiobookshelf server. Holds the configured
 * Retrofit client plus small in-memory caches shared by the UI and the
 * playback service.
 */
class AbsRepository(private val settings: SettingsStore) {

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

    suspend fun login(serverInput: String, username: String, password: String) {
        val server = normalizeServerUrl(serverInput)
        val anonymous = buildApi(server, token = null)
        val response = anonymous.login(LoginRequest(username, password))
        configure(server, response.user.token)
        settings.saveLogin(server, response.user.token, response.user.id)
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
        val libraryId = podcastLibraryId()
        val items = requireApi().libraryItems(libraryId).results
        podcastsCache = items
        return items
    }

    suspend fun podcast(itemId: String, forceRefresh: Boolean = false): LibraryItemExpanded {
        if (!forceRefresh) itemCache[itemId]?.let { return it }
        val item = requireApi().item(itemId)
        itemCache[itemId] = item
        return item
    }

    /** Server-side listening progress, cached briefly to avoid hammering /api/me. */
    suspend fun progress(itemId: String, episodeId: String, maxAgeMs: Long = 30_000): MediaProgress? {
        val now = System.currentTimeMillis()
        if (now - progressFetchedAt > maxAgeMs) {
            val me = requireApi().me()
            progressCache = me.mediaProgress
                .filter { it.episodeId != null }
                .associateBy { "${it.libraryItemId}:${it.episodeId}" }
            progressFetchedAt = now
        }
        return progressCache["$itemId:$episodeId"]
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
        fun normalizeServerUrl(input: String): String {
            var url = input.trim().trimEnd('/')
            if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            return url
        }
    }
}
