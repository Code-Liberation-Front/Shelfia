package app.shelfie.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerStatus(
    val isInit: Boolean = true,
    val authMethods: List<String> = emptyList(),
    val authFormData: AuthFormData? = null,
) {
    val supportsLocal: Boolean get() = authMethods.isEmpty() || authMethods.contains("local")
    val supportsOpenId: Boolean get() = authMethods.contains("openid")
}

@Serializable
data class AuthFormData(
    val authLoginCustomMessage: String? = null,
    val authOpenIDButtonText: String? = null,
    val authOpenIDAutoLaunch: Boolean = false,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val user: User,
)

@Serializable
data class User(
    val id: String = "",
    val username: String = "",
    val token: String = "",
    val mediaProgress: List<MediaProgress> = emptyList(),
)

@Serializable
data class MediaProgress(
    val id: String = "",
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0,
)

@Serializable
data class LibrariesResponse(
    val libraries: List<Library> = emptyList(),
)

@Serializable
data class Library(
    val id: String = "",
    val name: String = "",
    val mediaType: String = "",
)

@Serializable
data class LibraryItemsResponse(
    val results: List<LibraryItemSummary> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class LibraryItemSummary(
    val id: String = "",
    val addedAt: Long = 0,
    val media: MediaSummary = MediaSummary(),
)

@Serializable
data class MediaSummary(
    val metadata: PodcastMetadata = PodcastMetadata(),
    val numEpisodes: Int = 0,
)

@Serializable
data class LibraryItemExpanded(
    val id: String = "",
    val media: PodcastMedia = PodcastMedia(),
)

@Serializable
data class PodcastMedia(
    val metadata: PodcastMetadata = PodcastMetadata(),
    val episodes: List<PodcastEpisode> = emptyList(),
)

@Serializable
data class PodcastMetadata(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
)

@Serializable
data class PodcastEpisode(
    val id: String = "",
    val libraryItemId: String = "",
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val publishedAt: Long? = null,
    val season: String? = null,
    val episode: String? = null,
    val audioFile: AudioFile? = null,
    val audioTrack: AudioTrack? = null,
)

@Serializable
data class AudioFile(
    val ino: String = "",
    val duration: Double = 0.0,
    val mimeType: String? = null,
)

@Serializable
data class AudioTrack(
    val duration: Double = 0.0,
    val contentUrl: String? = null,
)

@Serializable
data class RecentEpisodesResponse(
    val episodes: List<PodcastEpisode> = emptyList(),
)

@Serializable
data class ListeningStats(
    val totalTime: Double = 0.0,
    val today: Double = 0.0,
)

@Serializable
data class ProgressUpdate(
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean = false,
)
