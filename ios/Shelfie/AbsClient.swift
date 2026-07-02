import Foundation

// MARK: - Models (Audiobookshelf REST API)

struct LoginResponse: Codable { let user: AbsUser }
struct AbsUser: Codable {
    let id: String
    let token: String
    let username: String?
    let mediaProgress: [MediaProgress]?
}

struct MediaProgress: Codable {
    let libraryItemId: String?
    let episodeId: String?
    let duration: Double?
    let progress: Double?
    let currentTime: Double?
    let isFinished: Bool?
    let lastUpdate: Double?
}

struct LibrariesResponse: Codable { let libraries: [AbsLibrary] }
struct AbsLibrary: Codable { let id: String; let name: String?; let mediaType: String? }

struct ItemsResponse: Codable { let results: [ItemSummary] }
struct ItemSummary: Codable, Identifiable {
    let id: String
    let media: SummaryMedia?
    var title: String { media?.metadata?.title ?? "Podcast" }
}
struct SummaryMedia: Codable { let metadata: MetaData? }
struct MetaData: Codable { let title: String?; let author: String?; let authorName: String? }

struct ItemExpanded: Codable {
    let id: String
    let media: ExpandedMedia?
    var title: String { media?.metadata?.title ?? "Podcast" }
}
struct ExpandedMedia: Codable { let metadata: MetaData?; let episodes: [Episode]? }

struct Episode: Codable, Identifiable {
    let id: String
    let libraryItemId: String?
    let title: String?
    let publishedAt: Double?
    let audioTrack: AudioTrack?
    let audioFile: AudioFile?
    var durationSec: Double { audioTrack?.duration ?? audioFile?.duration ?? 0 }
}
struct AudioTrack: Codable { let contentUrl: String?; let duration: Double? }
struct AudioFile: Codable { let duration: Double? }

struct ProgressUpdate: Codable {
    let currentTime: Double
    let duration: Double
    let progress: Double
    let isFinished: Bool
}

// MARK: - HTTP client

struct AbsError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

final class AbsClient {
    var serverUrl: String = ""
    var token: String = ""

    var isConfigured: Bool { !serverUrl.isEmpty && !token.isEmpty }

    private func request(_ path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        guard let url = URL(string: serverUrl + path) else {
            throw AbsError(message: "Invalid server URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        if !token.isEmpty {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: req)
        if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
            throw AbsError(message: "Server error HTTP \(http.statusCode)")
        }
        return data
    }

    private func get<T: Decodable>(_ path: String) async throws -> T {
        try JSONDecoder().decode(T.self, from: try await request(path))
    }

    static func normalize(_ input: String) -> String {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.hasPrefix("http://") && !s.hasPrefix("https://") { s = "https://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    func login(server: String, username: String, password: String) async throws -> AbsUser {
        serverUrl = Self.normalize(server)
        token = ""
        let body = try JSONSerialization.data(withJSONObject: ["username": username, "password": password])
        let data = try await request("/login", method: "POST", body: body)
        let response = try JSONDecoder().decode(LoginResponse.self, from: data)
        token = response.user.token
        return response.user
    }

    func libraries() async throws -> [AbsLibrary] {
        let r: LibrariesResponse = try await get("/api/libraries")
        return r.libraries
    }

    func items(libraryId: String) async throws -> [ItemSummary] {
        let r: ItemsResponse = try await get("/api/libraries/\(libraryId)/items?limit=500&sort=media.metadata.title")
        return r.results
    }

    func item(_ id: String) async throws -> ItemExpanded {
        try await get("/api/items/\(id)?expanded=1")
    }

    func me() async throws -> AbsUser {
        try await get("/api/me")
    }

    func updateProgress(itemId: String, episodeId: String, currentTime: Double, duration: Double) async throws {
        let progress = duration > 0 ? min(max(currentTime / duration, 0), 1) : 0
        let update = ProgressUpdate(
            currentTime: currentTime,
            duration: duration,
            progress: progress,
            isFinished: progress > 0.98
        )
        _ = try await request(
            "/api/me/progress/\(itemId)/\(episodeId)",
            method: "PATCH",
            body: try JSONEncoder().encode(update)
        )
    }

    func coverUrl(_ itemId: String) -> URL? {
        URL(string: "\(serverUrl)/api/items/\(itemId)/cover?token=\(token)")
    }

    func streamUrl(_ episode: Episode) -> URL? {
        guard let content = episode.audioTrack?.contentUrl else { return nil }
        let sep = content.contains("?") ? "&" : "?"
        return URL(string: "\(serverUrl)\(content)\(sep)token=\(token)")
    }
}

// MARK: - App state

@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()

    let client = AbsClient()
    @Published var loggedIn = false
    @Published var podcasts: [ItemSummary] = []
    @Published var progressByKey: [String: MediaProgress] = [:]

    private init() {
        let defaults = UserDefaults.standard
        if let server = defaults.string(forKey: "serverUrl"),
           let token = defaults.string(forKey: "token"),
           !server.isEmpty, !token.isEmpty {
            client.serverUrl = server
            client.token = token
            loggedIn = true
        }
    }

    func login(server: String, username: String, password: String) async throws {
        _ = try await client.login(server: server, username: username, password: password)
        UserDefaults.standard.set(client.serverUrl, forKey: "serverUrl")
        UserDefaults.standard.set(client.token, forKey: "token")
        loggedIn = true
    }

    func logout() {
        UserDefaults.standard.removeObject(forKey: "serverUrl")
        UserDefaults.standard.removeObject(forKey: "token")
        client.serverUrl = ""
        client.token = ""
        podcasts = []
        progressByKey = [:]
        loggedIn = false
    }

    /** Loads the podcast library (first podcast library on the server) and progress. */
    func refresh() async {
        guard client.isConfigured else { return }
        do {
            let libs = try await client.libraries()
            let lib = libs.first(where: { $0.mediaType == "podcast" }) ?? libs.first
            if let lib {
                podcasts = try await client.items(libraryId: lib.id)
            }
            await refreshProgress()
        } catch {
            // Keep whatever we have; UI shows cached state.
        }
    }

    func refreshProgress() async {
        guard let me = try? await client.me(), let entries = me.mediaProgress else { return }
        var map: [String: MediaProgress] = [:]
        for p in entries {
            if let item = p.libraryItemId {
                map["\(item):\(p.episodeId ?? "")"] = p
            }
        }
        progressByKey = map
    }

    func progressFor(itemId: String, episodeId: String) -> MediaProgress? {
        progressByKey["\(itemId):\(episodeId)"]
    }

    /** Unfinished episodes, most recently played first, resolved to podcast+episode. */
    func continueListening(limit: Int = 10) async -> [(podcast: ItemExpanded, episode: Episode, progress: MediaProgress)] {
        await refreshProgress()
        let unfinished = progressByKey.values
            .filter { ($0.isFinished != true) && (($0.currentTime ?? 0) > 0) && ($0.episodeId != nil) }
            .sorted { ($0.lastUpdate ?? 0) > ($1.lastUpdate ?? 0) }
            .prefix(limit)
        var out: [(ItemExpanded, Episode, MediaProgress)] = []
        for p in unfinished {
            guard let itemId = p.libraryItemId, let episodeId = p.episodeId else { continue }
            if let item = try? await client.item(itemId),
               let episode = item.media?.episodes?.first(where: { $0.id == episodeId }) {
                out.append((item, episode, p))
            }
        }
        return out
    }
}
