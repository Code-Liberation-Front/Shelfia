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
import java.util.UUID

@Serializable
data class PlaylistEntry(
    val itemId: String,
    val episodeId: String,
    val title: String,
    val podcastTitle: String,
)

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val entries: List<PlaylistEntry> = emptyList(),
)

/** Named, user-curated playlists of episodes, persisted to app storage. */
class PlaylistStore(context: Context) {

    private val file = File(context.filesDir, "playlists.json")
    private val legacyFile = File(context.filesDir, "playlist.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _playlists = MutableStateFlow(load())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        update(_playlists.value + UserPlaylist(id, name.trim().ifBlank { "Playlist" }))
        return id
    }

    fun delete(playlistId: String) {
        update(_playlists.value.filterNot { it.id == playlistId })
    }

    fun addTo(playlistId: String, entry: PlaylistEntry) {
        update(
            _playlists.value.map { playlist ->
                if (playlist.id == playlistId && playlist.entries.none { it.matches(entry) }) {
                    playlist.copy(entries = playlist.entries + entry)
                } else {
                    playlist
                }
            },
        )
    }

    fun removeFrom(playlistId: String, itemId: String, episodeId: String) {
        update(
            _playlists.value.map { playlist ->
                if (playlist.id == playlistId) {
                    playlist.copy(
                        entries = playlist.entries.filterNot {
                            it.itemId == itemId && it.episodeId == episodeId
                        },
                    )
                } else {
                    playlist
                }
            },
        )
    }

    fun toggleIn(playlistId: String, entry: PlaylistEntry) {
        val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        if (playlist.entries.any { it.matches(entry) }) {
            removeFrom(playlistId, entry.itemId, entry.episodeId)
        } else {
            addTo(playlistId, entry)
        }
    }

    fun isInAnyPlaylist(itemId: String, episodeId: String): Boolean =
        _playlists.value.any { playlist ->
            playlist.entries.any { it.itemId == itemId && it.episodeId == episodeId }
        }

    @Synchronized
    private fun update(playlists: List<UserPlaylist>) {
        _playlists.value = playlists
        runCatching { file.writeText(json.encodeToString(playlists)) }
    }

    private fun load(): List<UserPlaylist> {
        runCatching {
            if (file.exists()) {
                return json.decodeFromString<List<UserPlaylist>>(file.readText())
            }
        }
        // Migrate the single-playlist format from earlier builds.
        val legacy = runCatching {
            if (legacyFile.exists()) {
                json.decodeFromString<List<PlaylistEntry>>(legacyFile.readText())
            } else {
                null
            }
        }.getOrNull()
        return listOf(UserPlaylist(id = "default", name = "My Playlist", entries = legacy ?: emptyList()))
    }

    private fun PlaylistEntry.matches(other: PlaylistEntry): Boolean =
        itemId == other.itemId && episodeId == other.episodeId
}
