package app.shelfie.playlist

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PlaylistEntry(
    val itemId: String,
    val episodeId: String,
    val title: String,
    val podcastTitle: String,
)

/** A single user-curated playlist of episodes, persisted to app storage. */
class PlaylistStore(context: Context) {

    private val file = File(context.filesDir, "playlist.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<PlaylistEntry>> = _entries.asStateFlow()

    fun contains(itemId: String, episodeId: String): Boolean =
        _entries.value.any { it.itemId == itemId && it.episodeId == episodeId }

    fun toggle(entry: PlaylistEntry) {
        if (contains(entry.itemId, entry.episodeId)) {
            remove(entry.itemId, entry.episodeId)
        } else {
            add(entry)
        }
    }

    @Synchronized
    fun add(entry: PlaylistEntry) {
        if (contains(entry.itemId, entry.episodeId)) return
        update(_entries.value + entry)
    }

    @Synchronized
    fun remove(itemId: String, episodeId: String) {
        update(_entries.value.filterNot { it.itemId == itemId && it.episodeId == episodeId })
    }

    private fun update(entries: List<PlaylistEntry>) {
        _entries.value = entries
        runCatching { file.writeText(json.encodeToString(entries)) }
    }

    private fun load(): List<PlaylistEntry> = runCatching {
        if (file.exists()) json.decodeFromString<List<PlaylistEntry>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())
}
