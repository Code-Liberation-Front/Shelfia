import CryptoKit
import Foundation

struct AbsError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/**
 HTTP client for the Audiobookshelf REST API, mirroring the Android
 AbsApi/AbsRepository pair: Bearer-token auth for JSON endpoints, `?token=`
 query auth for media URLs, and a disk JSON cache so reads keep working
 offline.
 */
final class AbsClient {
    var serverUrl: String = ""
    var token: String = ""

    var isConfigured: Bool { !serverUrl.isEmpty && !token.isEmpty }

    private let cacheDir: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("apicache", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    static func normalize(_ input: String) -> String {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if !s.hasPrefix("http://") && !s.hasPrefix("https://") { s = "https://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    // MARK: Raw requests

    private func request(
        _ path: String,
        method: String = "GET",
        body: Data? = nil,
        server: String? = nil,
        authorized: Bool = true
    ) async throws -> Data {
        guard let url = URL(string: (server ?? serverUrl) + path) else {
            throw AbsError(message: "Invalid server URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        if authorized && !token.isEmpty {
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

    /** GET that falls back to the last successful on-disk response when offline. */
    private func getCached<T: Codable>(_ path: String, cacheKey: String) async throws -> T {
        do {
            let data = try await request(path)
            let value = try JSONDecoder().decode(T.self, from: data)
            try? data.write(to: cacheDir.appendingPathComponent(cacheKey))
            return value
        } catch {
            if let data = try? Data(contentsOf: cacheDir.appendingPathComponent(cacheKey)),
               let value = try? JSONDecoder().decode(T.self, from: data) {
                return value
            }
            throw error
        }
    }

    func clearCache() {
        try? FileManager.default.removeItem(at: cacheDir)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
    }

    // MARK: Auth

    func status(server: String) async throws -> ServerStatus {
        let data = try await request("/status", server: Self.normalize(server), authorized: false)
        return try JSONDecoder().decode(ServerStatus.self, from: data)
    }

    func login(server: String, username: String, password: String) async throws -> AbsUser {
        let normalized = Self.normalize(server)
        let body = try JSONSerialization.data(withJSONObject: ["username": username, "password": password])
        let data = try await request("/login", method: "POST", body: body, server: normalized, authorized: false)
        let response = try JSONDecoder().decode(LoginResponse.self, from: data)
        serverUrl = normalized
        token = response.user.token ?? ""
        return response.user
    }

    // MARK: OIDC (Audiobookshelf mobile contract, matching the Android flow)

    struct PendingOidc {
        let server: String
        let verifier: String
        let cookies: String
    }

    /**
     Requests /auth/openid without following the redirect, capturing the
     identity-provider URL and state cookies. The cookies must be replayed on
     /auth/openid/callback.
     */
    func startOidc(server: String) async throws -> (idpUrl: URL, pending: PendingOidc) {
        let normalized = Self.normalize(server)
        let verifier = Self.randomVerifier()
        let challenge = Self.codeChallenge(verifier)
        let redirect = "audiobookshelf://oauth"
            .addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        guard let url = URL(string:
            "\(normalized)/auth/openid?response_type=code&client_id=Shelfie" +
            "&redirect_uri=\(redirect)&code_challenge=\(challenge)&code_challenge_method=S256"
        ) else { throw AbsError(message: "Invalid server URL") }

        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        let session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
        let (body, response) = try await session.data(for: URLRequest(url: url))
        guard let http = response as? HTTPURLResponse else {
            throw AbsError(message: "Server did not respond")
        }
        guard (300...399).contains(http.statusCode) else {
            let detail = String(data: body.prefix(200), encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            throw AbsError(message: "Server did not start the sign-in flow (HTTP \(http.statusCode))"
                + (detail.isEmpty ? "" : ": \(detail)"))
        }
        guard let location = http.value(forHTTPHeaderField: "Location") else {
            throw AbsError(message: "Server did not return a sign-in redirect")
        }
        let resolved: String
        if location.hasPrefix("http://") || location.hasPrefix("https://") {
            resolved = location
        } else if location.hasPrefix("/") {
            resolved = normalized + location
        } else {
            resolved = normalized + "/" + location
        }
        guard let idpUrl = URL(string: resolved) else {
            throw AbsError(message: "Server returned an invalid sign-in redirect")
        }
        // URLSession merges repeated Set-Cookie headers; keep only name=value pairs.
        let setCookie = http.value(forHTTPHeaderField: "Set-Cookie") ?? ""
        let cookies = setCookie
            .components(separatedBy: ", ")
            .map { $0.components(separatedBy: ";")[0] }
            .filter { $0.contains("=") }
            .joined(separator: "; ")
        return (idpUrl, PendingOidc(server: normalized, verifier: verifier, cookies: cookies))
    }

    /** Completes the OIDC flow with the code/state delivered via the redirect. */
    func completeOidc(code: String, state: String, pending: PendingOidc) async throws -> AbsUser {
        var components = URLComponents(string: "\(pending.server)/auth/openid/callback")!
        components.queryItems = [
            URLQueryItem(name: "code", value: code),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_verifier", value: pending.verifier),
        ]
        var req = URLRequest(url: components.url!)
        if !pending.cookies.isEmpty {
            req.setValue(pending.cookies, forHTTPHeaderField: "Cookie")
        }
        let (data, response) = try await URLSession.shared.data(for: req)
        if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
            throw AbsError(message: "Sign-in failed (HTTP \(http.statusCode))")
        }
        let login = try JSONDecoder().decode(LoginResponse.self, from: data)
        serverUrl = pending.server
        token = login.user.token ?? ""
        return login.user
    }

    private final class NoRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(
            _ session: URLSession, task: URLSessionTask,
            willPerformHTTPRedirection response: HTTPURLResponse,
            newRequest request: URLRequest,
            completionHandler: @escaping (URLRequest?) -> Void
        ) { completionHandler(nil) }
    }

    private static func randomVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 64)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64URLEncoded()
    }

    private static func codeChallenge(_ verifier: String) -> String {
        Data(SHA256.hash(data: Data(verifier.utf8))).base64URLEncoded()
    }

    // MARK: API endpoints

    func me() async throws -> AbsUser {
        try await get("/api/me")
    }

    func libraries() async throws -> [AbsLibrary] {
        let r: LibrariesResponse = try await getCached("/api/libraries", cacheKey: "libraries.json")
        return r.libraries
    }

    func items(libraryId: String) async throws -> [LibraryItemSummary] {
        let r: LibraryItemsResponse = try await getCached(
            "/api/libraries/\(libraryId)/items?limit=500&sort=media.metadata.title",
            cacheKey: "items_\(libraryId).json"
        )
        return r.results
    }

    func recentEpisodes(libraryId: String, limit: Int = 75) async throws -> [PodcastEpisode] {
        let r: RecentEpisodesResponse = try await getCached(
            "/api/libraries/\(libraryId)/recent-episodes?limit=\(limit)",
            cacheKey: "latest_\(libraryId).json"
        )
        return r.episodes.sorted { ($0.publishedAt ?? 0) > ($1.publishedAt ?? 0) }
    }

    func item(_ id: String) async throws -> LibraryItemExpanded {
        try await getCached("/api/items/\(id)?expanded=1", cacheKey: "item_\(id).json")
    }

    func listeningStats() async throws -> ListeningStats {
        try await get("/api/me/listening-stats")
    }

    func updateEpisodeProgress(itemId: String, episodeId: String, update: ProgressUpdate) async throws {
        _ = try await request(
            "/api/me/progress/\(itemId)/\(episodeId)",
            method: "PATCH",
            body: try JSONEncoder().encode(update)
        )
    }

    func updateBookProgress(itemId: String, update: ProgressUpdate) async throws {
        _ = try await request(
            "/api/me/progress/\(itemId)",
            method: "PATCH",
            body: try JSONEncoder().encode(update)
        )
    }

    // MARK: Media URLs (token as query parameter)

    func coverUrl(_ itemId: String) -> URL? {
        URL(string: "\(serverUrl)/api/items/\(itemId)/cover?token=\(token)")
    }

    func tokenized(_ contentUrl: String) -> URL? {
        let sep = contentUrl.contains("?") ? "&" : "?"
        return URL(string: "\(serverUrl)\(contentUrl)\(sep)token=\(token)")
    }

    func streamUrl(itemId: String, episode: PodcastEpisode) -> URL? {
        if let content = episode.audioTrack?.contentUrl { return tokenized(content) }
        if let ino = episode.audioFile?.ino, !ino.isEmpty {
            return URL(string: "\(serverUrl)/api/items/\(itemId)/file/\(ino)?token=\(token)")
        }
        return nil
    }

    func trackUrl(_ track: BookTrack) -> URL? {
        guard let content = track.contentUrl else { return nil }
        return tokenized(content)
    }
}

private extension Data {
    func base64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
