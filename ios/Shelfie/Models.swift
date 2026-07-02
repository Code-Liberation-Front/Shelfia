import Foundation

// MARK: - Audiobookshelf REST API models

struct ServerStatus: Codable {
    var isInit: Bool? = true
    var authMethods: [String]? = []
    var authFormData: AuthFormData?

    var supportsLocal: Bool {
        let methods = authMethods ?? []
        return methods.isEmpty || methods.contains("local")
    }
    var supportsOpenId: Bool { (authMethods ?? []).contains("openid") }
}

struct AuthFormData: Codable {
    var authLoginCustomMessage: String?
    var authOpenIDButtonText: String?
    var authOpenIDAutoLaunch: Bool?
}

struct LoginResponse: Codable { let user: AbsUser }

struct AbsUser: Codable {
    var id: String? = ""
    var username: String? = ""
    var token: String? = ""
    var mediaProgress: [MediaProgress]? = []
}

struct MediaProgress: Codable {
    var id: String? = ""
    var libraryItemId: String? = ""
    var episodeId: String?
    var duration: Double? = 0
    var progress: Double? = 0
    var currentTime: Double? = 0
    var isFinished: Bool? = false
    var lastUpdate: Double? = 0

    var finished: Bool { isFinished == true }
    var fraction: Double { min(max(progress ?? 0, 0), 1) }
}

struct LibrariesResponse: Codable { var libraries: [AbsLibrary] = [] }

struct AbsLibrary: Codable, Identifiable, Hashable {
    var id: String = ""
    var name: String? = ""
    var mediaType: String? = ""
}

struct LibraryItemsResponse: Codable {
    var results: [LibraryItemSummary] = []
    var total: Int? = 0
}

struct LibraryItemSummary: Codable, Identifiable, Hashable {
    static func == (lhs: LibraryItemSummary, rhs: LibraryItemSummary) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }

    var id: String = ""
    var addedAt: Double? = 0
    var media: MediaSummary? = MediaSummary()

    var title: String { media?.metadata?.title ?? "Untitled" }
    var author: String? { media?.metadata?.displayAuthor }
    var numEpisodes: Int { media?.numEpisodes ?? 0 }
}

struct MediaSummary: Codable, Hashable {
    var metadata: ItemMetadata? = ItemMetadata()
    var numEpisodes: Int? = 0
}

struct LibraryItemExpanded: Codable, Identifiable {
    var id: String = ""
    var media: ExpandedMedia? = ExpandedMedia()

    var title: String { media?.metadata?.title ?? "Untitled" }
    var author: String? { media?.metadata?.displayAuthor }
    var episodes: [PodcastEpisode] { media?.episodes ?? [] }
    var tracks: [BookTrack] { media?.tracks ?? [] }
    var isBook: Bool { episodes.isEmpty && !tracks.isEmpty }
    var bookDuration: Double {
        let d = media?.duration ?? 0
        return d > 0 ? d : tracks.reduce(0) { $0 + $1.durationSec }
    }
}

struct ExpandedMedia: Codable {
    var metadata: ItemMetadata? = ItemMetadata()
    var episodes: [PodcastEpisode]? = []
    // Audiobook/MP3 library items expose audio tracks instead of episodes.
    var tracks: [BookTrack]? = []
    var duration: Double? = 0
}

struct ItemMetadata: Codable, Hashable {
    var title: String?
    var author: String?
    // Book metadata uses authorName instead of author.
    var authorName: String?
    var description: String?

    var displayAuthor: String? { author ?? authorName }
}

struct BookTrack: Codable, Identifiable {
    var index: Int? = 0
    var startOffset: Double? = 0
    var duration: Double? = 0
    var title: String?
    var contentUrl: String?

    var id: Int { index ?? 0 }
    var startOffsetSec: Double { startOffset ?? 0 }
    var durationSec: Double { duration ?? 0 }
}

struct PodcastEpisode: Codable, Identifiable {
    var id: String = ""
    var libraryItemId: String? = ""
    var title: String?
    var subtitle: String?
    var description: String?
    var publishedAt: Double?
    var pubDate: String?
    var season: String?
    var episode: String?
    var audioFile: AudioFile?
    var audioTrack: AudioTrack?

    var durationSec: Double { audioTrack?.duration ?? audioFile?.duration ?? 0 }
    var publishedDate: Date? {
        guard let ms = publishedAt, ms > 0 else { return nil }
        return Date(timeIntervalSince1970: ms / 1000)
    }
}

struct AudioFile: Codable {
    var ino: String? = ""
    var duration: Double? = 0
    var mimeType: String?
}

struct AudioTrack: Codable {
    var duration: Double? = 0
    var contentUrl: String?
}

struct RecentEpisodesResponse: Codable { var episodes: [PodcastEpisode] = [] }

struct ListeningStats: Codable {
    var totalTime: Double? = 0
    var today: Double? = 0
}

struct ProgressUpdate: Codable {
    let currentTime: Double
    let duration: Double
    let progress: Double
    let isFinished: Bool
}

// MARK: - Formatting helpers (parity with Android Format.kt)

func formatDuration(_ seconds: Double) -> String {
    let total = Int(seconds.rounded())
    let h = total / 3600, m = (total % 3600) / 60, s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

func formatListeningTime(_ seconds: Double) -> String {
    let total = Int(seconds)
    let h = total / 3600, m = (total % 3600) / 60
    return h > 0 ? "\(h)h \(m)m" : "\(m)m"
}

func formatBytes(_ bytes: Int64) -> String {
    let gb = 1024.0 * 1024 * 1024, mb = 1024.0 * 1024, kb = 1024.0
    let b = Double(bytes)
    if b >= gb { return String(format: "%.1f GB", b / gb) }
    if b >= mb { return String(format: "%.1f MB", b / mb) }
    return String(format: "%.0f KB", b / kb)
}

private let rssDateFormats = [
    "EEE, dd MMM yyyy HH:mm:ss Z",
    "EEE, dd MMM yyyy HH:mm Z",
]

func episodeDate(_ episode: PodcastEpisode) -> Date? {
    if let date = episode.publishedDate { return date }
    guard let pub = episode.pubDate else { return nil }
    for format in rssDateFormats {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = format
        if let date = formatter.date(from: pub) { return date }
    }
    return nil
}

func stripHtml(_ text: String) -> String {
    text.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
        .replacingOccurrences(of: "&amp;", with: "&")
        .replacingOccurrences(of: "&nbsp;", with: " ")
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
}
