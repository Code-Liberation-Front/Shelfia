import Combine
import Foundation

struct DownloadedEpisode: Codable, Identifiable {
    let itemId: String
    let episodeId: String
    let title: String
    let podcastTitle: String
    let fileName: String
    let sizeBytes: Int64
    let downloadedAt: Double
    var durationSec: Double? = 0

    var id: String { "\(itemId):\(episodeId)" }
}

struct ActiveDownload: Identifiable {
    let itemId: String
    let episodeId: String
    let title: String
    var bytes: Int64 = 0
    var total: Int64 = 0
    var speedBytesPerSec: Double = 0
    var failed = false

    var id: String { "\(itemId):\(episodeId)" }
    var fraction: Double? { total > 0 ? Double(bytes) / Double(total) : nil }
}

/**
 Manual per-episode downloads with progress/speed reporting and a JSON index,
 mirroring the Android DownloadCenter. Files live in
 Documents/episodes/{itemId}_{episodeId}.audio.
 */
@MainActor
final class DownloadCenter: NSObject, ObservableObject {
    static let shared = DownloadCenter()

    @Published private(set) var downloaded: [DownloadedEpisode] = []
    @Published private(set) var active: [String: ActiveDownload] = [:]

    private var tasks: [String: URLSessionDownloadTask] = [:]
    private var lastSample: [String: (Date, Int64)] = [:]
    private var durations: [String: Double] = [:]

    private let dir: URL = {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("episodes", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private var indexUrl: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("downloads_index.json")
    }

    override private init() {
        super.init()
        if let data = try? Data(contentsOf: indexUrl),
           let list = try? JSONDecoder().decode([DownloadedEpisode].self, from: data) {
            // Drop index entries whose file vanished.
            downloaded = list.filter {
                FileManager.default.fileExists(atPath: dir.appendingPathComponent($0.fileName).path)
            }
        }
    }

    var totalSizeBytes: Int64 { downloaded.reduce(0) { $0 + $1.sizeBytes } }

    func isDownloaded(itemId: String, episodeId: String) -> Bool {
        downloaded.contains { $0.itemId == itemId && $0.episodeId == episodeId }
    }

    func isDownloading(itemId: String, episodeId: String) -> Bool {
        active["\(itemId):\(episodeId)"] != nil
    }

    func entry(itemId: String, episodeId: String) -> DownloadedEpisode? {
        downloaded.first { $0.itemId == itemId && $0.episodeId == episodeId }
    }

    /** Local file URL when the episode is downloaded, else nil. */
    func localUrl(itemId: String, episodeId: String) -> URL? {
        guard let entry = entry(itemId: itemId, episodeId: episodeId) else { return nil }
        let url = dir.appendingPathComponent(entry.fileName)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func start(podcast: LibraryItemExpanded, episode: PodcastEpisode) {
        let key = "\(podcast.id):\(episode.id)"
        guard active[key] == nil, !isDownloaded(itemId: podcast.id, episodeId: episode.id),
              let url = AppState.shared.client.streamUrl(itemId: podcast.id, episode: episode)
        else { return }

        active[key] = ActiveDownload(
            itemId: podcast.id, episodeId: episode.id,
            title: episode.title ?? "Episode"
        )
        durations[key] = episode.durationSec

        let podcastTitle = podcast.title
        let task = Network.session.downloadTask(with: url) { [weak self] tmp, response, error in
            Task { @MainActor in
                guard let self else { return }
                self.tasks[key] = nil
                guard let tmp, error == nil,
                      (response as? HTTPURLResponse).map({ $0.statusCode < 400 }) ?? true
                else {
                    self.markFailed(key)
                    return
                }
                self.finish(
                    key: key, tmp: tmp,
                    itemId: podcast.id, episodeId: episode.id,
                    title: episode.title ?? "Episode", podcastTitle: podcastTitle
                )
            }
        }
        tasks[key] = task
        observeProgress(task: task, key: key)
        task.resume()
    }

    private func observeProgress(task: URLSessionDownloadTask, key: String) {
        // Poll the task's progress every 500ms like the Android speed sampler.
        Task { [weak self] in
            while let self, self.tasks[key] === task {
                let received = task.countOfBytesReceived
                let expected = task.countOfBytesExpectedToReceive
                if var current = self.active[key] {
                    let now = Date()
                    if let (t, bytes) = self.lastSample[key] {
                        let dt = now.timeIntervalSince(t)
                        if dt > 0 {
                            current.speedBytesPerSec = Double(received - bytes) / dt
                        }
                    }
                    self.lastSample[key] = (now, received)
                    current.bytes = max(received, 0)
                    current.total = max(expected, 0)
                    self.active[key] = current
                }
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    private func finish(
        key: String, tmp: URL,
        itemId: String, episodeId: String, title: String, podcastTitle: String
    ) {
        let fileName = sanitize("\(itemId)_\(episodeId)") + ".audio"
        let dest = dir.appendingPathComponent(fileName)
        do {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: tmp, to: dest)
            let attrs = try? FileManager.default.attributesOfItem(atPath: dest.path)
            let size = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
            downloaded.removeAll { $0.itemId == itemId && $0.episodeId == episodeId }
            downloaded.append(DownloadedEpisode(
                itemId: itemId, episodeId: episodeId,
                title: title, podcastTitle: podcastTitle,
                fileName: fileName, sizeBytes: size,
                downloadedAt: Date().timeIntervalSince1970 * 1000,
                durationSec: durations[key] ?? 0
            ))
            saveIndex()
            active[key] = nil
            lastSample[key] = nil
        } catch {
            markFailed(key)
        }
    }

    private func markFailed(_ key: String) {
        guard var current = active[key] else { return }
        current.failed = true
        active[key] = current
        lastSample[key] = nil
        // Failed downloads linger 5s then clear, like Android.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            await MainActor.run {
                if self?.active[key]?.failed == true { self?.active[key] = nil }
            }
        }
    }

    func cancel(itemId: String, episodeId: String) {
        let key = "\(itemId):\(episodeId)"
        tasks[key]?.cancel()
        tasks[key] = nil
        active[key] = nil
        lastSample[key] = nil
    }

    func delete(itemId: String, episodeId: String) {
        if let entry = entry(itemId: itemId, episodeId: episodeId) {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(entry.fileName))
        }
        downloaded.removeAll { $0.itemId == itemId && $0.episodeId == episodeId }
        saveIndex()
    }

    private func saveIndex() {
        if let data = try? JSONEncoder().encode(downloaded) {
            try? data.write(to: indexUrl)
        }
    }

    private func sanitize(_ name: String) -> String {
        name.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
    }
}
