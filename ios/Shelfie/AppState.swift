import Combine
import Foundation
import Network

/**
 App-wide session state: login, libraries, cached library content, and the
 listening-progress map (30s cache, revision-bumped on explicit changes),
 mirroring the Android AbsRepository behavior.
 */
@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()

    let client = AbsClient()

    @Published var loggedIn = false
    @Published var libraries: [AbsLibrary] = []
    @Published var activeLibraryId: String = ""
    @Published var podcasts: [LibraryItemSummary] = []
    @Published var latest: [PodcastEpisode] = []
    @Published var progressByKey: [String: MediaProgress] = [:]
    /** Bumped whenever progress changes locally so screens can reload. */
    @Published var progressRevision = 0
    @Published var isOnline = true

    private var progressFetchedAt: Date = .distantPast
    private var itemCache: [String: LibraryItemExpanded] = [:]
    private let pathMonitor = NWPathMonitor()

    private init() {
        if !Settings.serverUrl.isEmpty && !Settings.token.isEmpty {
            client.serverUrl = Settings.serverUrl
            client.token = Settings.token
            loggedIn = true
        }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in self?.isOnline = path.status == .satisfied }
        }
        pathMonitor.start(queue: DispatchQueue(label: "connectivity"))
    }

    var activeLibrary: AbsLibrary? { libraries.first { $0.id == activeLibraryId } }
    var activeLibraryIsPodcast: Bool { activeLibrary?.mediaType != "book" }

    // MARK: Session

    func login(server: String, username: String, password: String) async throws {
        let user = try await client.login(server: server, username: username, password: password)
        Settings.saveLogin(
            server: client.serverUrl, token: client.token,
            userId: user.id ?? "", username: user.username ?? ""
        )
        loggedIn = true
    }

    func completeOidcLogin(code: String, state: String, pending: AbsClient.PendingOidc) async throws {
        let user = try await client.completeOidc(code: code, state: state, pending: pending)
        Settings.saveLogin(
            server: client.serverUrl, token: client.token,
            userId: user.id ?? "", username: user.username ?? ""
        )
        loggedIn = true
    }

    func logout() {
        Settings.clearLogin()
        client.serverUrl = ""
        client.token = ""
        client.clearCache()
        podcasts = []
        latest = []
        libraries = []
        activeLibraryId = ""
        progressByKey = [:]
        itemCache = [:]
        loggedIn = false
        PlayerManager.shared.stop()
    }

    // MARK: Library

    func refresh(force: Bool = false) async {
        guard client.isConfigured else { return }
        do {
            libraries = try await client.libraries()
            let saved = Settings.libraryId
            if !saved.isEmpty, libraries.contains(where: { $0.id == saved }) {
                activeLibraryId = saved
            } else {
                activeLibraryId = libraries.first(where: { $0.mediaType == "podcast" })?.id
                    ?? libraries.first?.id ?? ""
            }
            if !activeLibraryId.isEmpty {
                podcasts = try await client.items(libraryId: activeLibraryId)
            }
            await refreshProgress(force: force)
            await refreshLatest()
        } catch {
            // Keep whatever we have; disk cache already softened network reads.
        }
    }

    func selectLibrary(_ id: String) async {
        Settings.libraryId = id
        activeLibraryId = id
        podcasts = (try? await client.items(libraryId: id)) ?? []
        latest = []
        await refreshLatest()
    }

    func refreshLatest() async {
        guard !activeLibraryId.isEmpty, activeLibraryIsPodcast else {
            latest = []
            return
        }
        latest = (try? await client.recentEpisodes(libraryId: activeLibraryId)) ?? latest
    }

    /** Item detail with an in-memory cache (network layer adds a disk cache). */
    func item(_ id: String, force: Bool = false) async -> LibraryItemExpanded? {
        if !force, let cached = itemCache[id] { return cached }
        guard let item = try? await client.item(id) else { return itemCache[id] }
        itemCache[id] = item
        return item
    }

    func recentlyAdded(limit: Int = 12) -> [LibraryItemSummary] {
        podcasts.sorted { ($0.addedAt ?? 0) > ($1.addedAt ?? 0) }.prefix(limit).map { $0 }
    }

    // MARK: Progress

    func refreshProgress(force: Bool = false) async {
        let maxAge: TimeInterval = force ? 0 : 30
        guard Date().timeIntervalSince(progressFetchedAt) > maxAge else { return }
        guard let me = try? await client.me(), let entries = me.mediaProgress else { return }
        var map: [String: MediaProgress] = [:]
        for p in entries {
            map["\(p.libraryItemId ?? ""):\(p.episodeId ?? "")"] = p
        }
        progressByKey = map
        progressFetchedAt = Date()
    }

    func progressFor(itemId: String, episodeId: String?) -> MediaProgress? {
        progressByKey["\(itemId):\(episodeId ?? "")"]
    }

    func bookProgress(itemId: String) -> MediaProgress? {
        progressFor(itemId: itemId, episodeId: nil)
    }

    /** Applies a progress write locally and pushes it to the server. */
    func pushProgress(
        itemId: String, episodeId: String?, currentTime: Double, duration: Double
    ) async {
        let fraction = duration > 0 ? min(max(currentTime / duration, 0), 1) : 0
        let update = ProgressUpdate(
            currentTime: currentTime,
            duration: duration,
            progress: fraction,
            isFinished: fraction > 0.98
        )
        applyLocal(itemId: itemId, episodeId: episodeId, update: update)
        if let episodeId, !episodeId.isEmpty {
            try? await client.updateEpisodeProgress(itemId: itemId, episodeId: episodeId, update: update)
        } else {
            try? await client.updateBookProgress(itemId: itemId, update: update)
        }
    }

    func setFinished(itemId: String, episodeId: String?, finished: Bool, duration: Double) async {
        let update = ProgressUpdate(
            currentTime: finished ? duration : 0,
            duration: duration,
            progress: finished ? 1 : 0,
            isFinished: finished
        )
        applyLocal(itemId: itemId, episodeId: episodeId, update: update)
        if let episodeId, !episodeId.isEmpty {
            try? await client.updateEpisodeProgress(itemId: itemId, episodeId: episodeId, update: update)
        } else {
            try? await client.updateBookProgress(itemId: itemId, update: update)
        }
        progressRevision += 1
    }

    func resetProgress(itemId: String, episodeId: String?, duration: Double) async {
        await setFinished(itemId: itemId, episodeId: episodeId, finished: false, duration: duration)
    }

    private func applyLocal(itemId: String, episodeId: String?, update: ProgressUpdate) {
        let key = "\(itemId):\(episodeId ?? "")"
        var entry = progressByKey[key] ?? MediaProgress(
            libraryItemId: itemId,
            episodeId: (episodeId?.isEmpty == true) ? nil : episodeId
        )
        entry.currentTime = update.currentTime
        entry.duration = update.duration
        entry.progress = update.progress
        entry.isFinished = update.isFinished
        entry.lastUpdate = Date().timeIntervalSince1970 * 1000
        progressByKey[key] = entry
    }

    // MARK: Aggregations

    struct InProgressEpisode: Identifiable {
        let podcast: LibraryItemExpanded
        let episode: PodcastEpisode
        let progress: Double
        var id: String { "\(podcast.id):\(episode.id)" }
    }

    /** Episodes started but unfinished, most recently played first. */
    func continueListening(limit: Int = 12, force: Bool = false) async -> [InProgressEpisode] {
        await refreshProgress(force: force)
        let unfinished = progressByKey.values
            .filter { $0.episodeId != nil && !$0.finished && ($0.currentTime ?? 0) > 0 }
            .sorted { ($0.lastUpdate ?? 0) > ($1.lastUpdate ?? 0) }
            .prefix(limit)
        var out: [InProgressEpisode] = []
        for p in unfinished {
            guard let itemId = p.libraryItemId, let episodeId = p.episodeId,
                  let podcast = await item(itemId),
                  let episode = podcast.episodes.first(where: { $0.id == episodeId })
            else { continue }
            out.append(InProgressEpisode(podcast: podcast, episode: episode, progress: p.fraction))
        }
        return out
    }

    /** Client-side search over cached podcasts and episodes (Android parity). */
    func search(_ query: String, maxPodcastFetches: Int = 20)
        async -> (podcasts: [LibraryItemSummary], episodes: [(LibraryItemExpanded, PodcastEpisode)])
    {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else { return ([], []) }
        let podcastMatches = podcasts.filter {
            $0.title.lowercased().contains(needle)
                || ($0.author ?? "").lowercased().contains(needle)
        }
        var episodeMatches: [(LibraryItemExpanded, PodcastEpisode)] = []
        for summary in podcasts.prefix(maxPodcastFetches) {
            guard let podcast = await item(summary.id) else { continue }
            let matches = podcast.episodes
                .filter { ($0.title ?? "").lowercased().contains(needle) }
                .sorted { ($0.publishedAt ?? 0) > ($1.publishedAt ?? 0) }
                .prefix(5)
            episodeMatches.append(contentsOf: matches.map { (podcast, $0) })
            if episodeMatches.count >= 25 { break }
        }
        return (podcastMatches, episodeMatches)
    }
}
