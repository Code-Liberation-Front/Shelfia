package app.shelfie.download

import android.content.Context
import android.net.Uri
import app.shelfie.data.AbsRepository
import app.shelfie.data.LibraryItemExpanded
import app.shelfie.data.PodcastEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class DownloadedEpisode(
    val itemId: String,
    val episodeId: String,
    val title: String,
    val podcastTitle: String,
    val fileName: String,
    val sizeBytes: Long,
    val downloadedAt: Long,
)

data class ActiveDownload(
    val key: String,
    val title: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val failed: Boolean = false,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Downloads episodes to app-private storage and tracks both in-flight progress
 * (bytes, total, speed) and the completed-download index. Completed episodes are
 * played from disk, which also works with no network connection.
 */
class DownloadCenter(context: Context, private val repo: AbsRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "episodes").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "downloads_index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _completed = MutableStateFlow(loadIndex())
    val completed: StateFlow<List<DownloadedEpisode>> = _completed.asStateFlow()

    private val _active = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val active: StateFlow<Map<String, ActiveDownload>> = _active.asStateFlow()

    fun key(itemId: String, episodeId: String): String = "$itemId:$episodeId"

    fun isDownloaded(itemId: String, episodeId: String): Boolean =
        _completed.value.any { it.itemId == itemId && it.episodeId == episodeId }

    /** file:// URI of a completed download, or null if not downloaded (or file missing). */
    fun localUri(itemId: String, episodeId: String): Uri? {
        val entry = _completed.value.firstOrNull { it.itemId == itemId && it.episodeId == episodeId }
            ?: return null
        val file = File(dir, entry.fileName)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    fun totalDownloadedBytes(): Long = _completed.value.sumOf { it.sizeBytes }

    fun download(podcast: LibraryItemExpanded, episode: PodcastEpisode) {
        val key = key(podcast.id, episode.id)
        if (isDownloaded(podcast.id, episode.id) || jobs.containsKey(key)) return
        val url = repo.streamUrl(podcast.id, episode) ?: return
        val title = episode.title ?: "Episode"
        val podcastTitle = podcast.media.metadata.title ?: ""
        val safeName = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tmp = File(dir, "$safeName.part")
        val final = File(dir, "$safeName.audio")

        _active.update { it + (key to ActiveDownload(key, title, 0, 0, 0)) }
        val job = scope.launch {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response")
                    val total = body.contentLength()
                    var bytes = 0L
                    var lastBytes = 0L
                    var lastTime = System.currentTimeMillis()
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                bytes += read
                                val now = System.currentTimeMillis()
                                if (now - lastTime >= 500) {
                                    val speed = (bytes - lastBytes) * 1000 / (now - lastTime)
                                    lastBytes = bytes
                                    lastTime = now
                                    _active.update {
                                        it + (key to ActiveDownload(key, title, bytes, total, speed))
                                    }
                                }
                            }
                        }
                    }
                }
                if (!tmp.renameTo(final)) throw IOException("Could not move download into place")
                addToIndex(
                    DownloadedEpisode(
                        itemId = podcast.id,
                        episodeId = episode.id,
                        title = title,
                        podcastTitle = podcastTitle,
                        fileName = final.name,
                        sizeBytes = final.length(),
                        downloadedAt = System.currentTimeMillis(),
                    ),
                )
                _active.update { it - key }
            } catch (e: CancellationException) {
                tmp.delete()
                _active.update { it - key }
                throw e
            } catch (e: Exception) {
                tmp.delete()
                _active.update { it + (key to ActiveDownload(key, title, 0, 0, 0, failed = true)) }
                // Leave the failure visible briefly, then clear it.
                delay(5_000)
                _active.update { map -> if (map[key]?.failed == true) map - key else map }
            } finally {
                jobs.remove(key)
            }
        }
        jobs[key] = job
    }

    fun cancel(key: String) {
        jobs[key]?.cancel()
    }

    fun delete(entry: DownloadedEpisode) {
        File(dir, entry.fileName).delete()
        val updated = _completed.value.filterNot {
            it.itemId == entry.itemId && it.episodeId == entry.episodeId
        }
        _completed.value = updated
        saveIndex(updated)
    }

    @Synchronized
    private fun addToIndex(entry: DownloadedEpisode) {
        val updated = _completed.value
            .filterNot { it.itemId == entry.itemId && it.episodeId == entry.episodeId } + entry
        _completed.value = updated
        saveIndex(updated)
    }

    private fun loadIndex(): List<DownloadedEpisode> = runCatching {
        if (indexFile.exists()) {
            json.decodeFromString<List<DownloadedEpisode>>(indexFile.readText())
        } else {
            emptyList()
        }
    }.getOrDefault(emptyList())

    private fun saveIndex(entries: List<DownloadedEpisode>) {
        runCatching { indexFile.writeText(json.encodeToString(entries)) }
    }
}
