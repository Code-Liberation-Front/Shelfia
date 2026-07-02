import AVFoundation
import Combine
import Foundation
import MediaPlayer
import UIKit

/** One playable element in the logical queue (podcast episode or book track). */
struct QueueEntry: Equatable {
    enum Kind: Equatable {
        case episode(itemId: String, episodeId: String)
        case bookTrack(itemId: String, trackIndex: Int, startOffset: Double, bookDuration: Double)
    }

    let kind: Kind
    let title: String
    let podcastTitle: String
    let url: URL
    let coverUrl: URL?
    let durationSec: Double
    let publishedAt: Double?

    var itemId: String {
        switch kind {
        case .episode(let itemId, _): return itemId
        case .bookTrack(let itemId, _, _, _): return itemId
        }
    }

    var episodeId: String? {
        if case .episode(_, let episodeId) = kind { return episodeId }
        return nil
    }

    /** Persistable id matching Android's mediaId scheme. */
    var mediaId: String {
        switch kind {
        case .episode(let itemId, let episodeId): return "episode:\(itemId):\(episodeId)"
        case .bookTrack(let itemId, let index, _, _): return "track:\(itemId):\(index)"
        }
    }
}

/**
 Single AVPlayer shared by the phone UI and CarPlay. Mirrors the Android
 PlaybackService: 10s/30s skips, whole-podcast auto-play queues, audiobook
 track queues on the whole-book progress timeline, resume from server
 position, and progress sync on pause plus every 15 seconds.
 */
@MainActor
final class PlayerManager: ObservableObject {
    static let shared = PlayerManager()

    @Published private(set) var queue: [QueueEntry] = []
    @Published private(set) var queueIndex = 0
    @Published var isPlaying = false
    @Published var isBuffering = false
    @Published var positionSec: Double = 0
    @Published var rate: Float = 1.0

    var current: QueueEntry? {
        queue.indices.contains(queueIndex) ? queue[queueIndex] : nil
    }

    private var player: AVPlayer?
    private var timeObserver: Any?
    private var syncTask: Task<Void, Never>?
    private var endObserver: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var artworkCache: (URL, MPMediaItemArtwork)?

    private init() {
        setupRemoteCommands()
    }

    // MARK: Queue building (Android PlaybackService parity)

    /**
     Auto-play direction: picking an older episode continues forward in time
     (695 → 696 → 697); picking the newest walks backwards (700 → 699). Either
     way the queue stops before the first fully-played episode. With auto-play
     off only the selected episode queues.
     */
    func play(podcast: LibraryItemExpanded, episode: PodcastEpisode) {
        if let now = current, now.mediaId == "episode:\(podcast.id):\(episode.id)" {
            togglePlayPause()
            return
        }
        let episodes: [PodcastEpisode]
        if Settings.autoPlay {
            let chronological = podcast.episodes.sorted { ($0.publishedAt ?? 0) < ($1.publishedAt ?? 0) }
            guard let start = chronological.firstIndex(where: { $0.id == episode.id }) else { return }
            let direction = start == chronological.count - 1 ? -1 : 1
            var list = [chronological[start]]
            var index = start + direction
            while chronological.indices.contains(index) {
                let next = chronological[index]
                if AppState.shared.progressFor(itemId: podcast.id, episodeId: next.id)?.finished == true {
                    break
                }
                list.append(next)
                index += direction
            }
            episodes = list
        } else {
            episodes = [episode]
        }
        let entries = episodes.compactMap { entry(podcast: podcast, episode: $0) }
        guard !entries.isEmpty else { return }
        startQueue(entries, at: 0, resumeFromServer: true)
    }

    /** Queues playlist entries in order, starting at the tapped entry. */
    func playPlaylist(_ entries: [PlaylistEntry], startAt: Int) async {
        var resolved: [QueueEntry] = []
        var startIndex = 0
        for (offset, playlistEntry) in entries.enumerated() {
            if let entry = await resolve(playlistEntry) {
                if offset == startAt { startIndex = resolved.count }
                resolved.append(entry)
            } else if offset == startAt {
                startIndex = resolved.count
            }
        }
        guard !resolved.isEmpty else { return }
        startQueue(resolved, at: min(startIndex, resolved.count - 1), resumeFromServer: true)
    }

    /** Fully local queue from the download index (works offline). */
    func playDownloaded(_ list: [DownloadedEpisode], startAt: Int) {
        let entries = list.compactMap { entry(downloaded: $0) }
        guard !entries.isEmpty else { return }
        startQueue(entries, at: min(startAt, entries.count - 1), resumeFromServer: true)
    }

    /** Queues all book tracks starting at the tapped one (whole-book timeline). */
    func playBook(item: LibraryItemExpanded, trackIndex: Int) {
        if let now = current, now.mediaId == "track:\(item.id):\(trackIndex)" {
            togglePlayPause()
            return
        }
        let sorted = item.tracks.sorted { ($0.index ?? 0) < ($1.index ?? 0) }
        guard let start = sorted.firstIndex(where: { ($0.index ?? 0) == trackIndex }) else { return }
        let entries = sorted[start...].compactMap { entry(item: item, track: $0) }
        guard !entries.isEmpty else { return }
        startQueue(Array(entries), at: 0, resumeFromServer: true)
    }

    /** Resumes a whole book from the server-side book time. */
    func resumeBook(item: LibraryItemExpanded) {
        let progress = AppState.shared.bookProgress(itemId: item.id)
        let bookTime = progress?.finished == true ? 0 : (progress?.currentTime ?? 0)
        let sorted = item.tracks.sorted { ($0.index ?? 0) < ($1.index ?? 0) }
        let containing = sorted.first {
            bookTime >= $0.startOffsetSec && bookTime < $0.startOffsetSec + $0.durationSec
        } ?? sorted.first
        guard let track = containing else { return }
        let start = sorted.firstIndex { ($0.index ?? 0) == (track.index ?? 0) } ?? 0
        let entries = sorted[start...].compactMap { entry(item: item, track: $0) }
        guard !entries.isEmpty else { return }
        startQueue(Array(entries), at: 0, position: max(bookTime - track.startOffsetSec, 0))
    }

    /** Rebuilds playback from the last-played id (CarPlay resumption). */
    func resumeLastPlayed() async {
        let mediaId = Settings.lastPlayedMediaId
        let parts = mediaId.components(separatedBy: ":")
        guard parts.count == 3 else { return }
        if parts[0] == "episode" {
            if let podcast = await AppState.shared.item(parts[1]),
               let episode = podcast.episodes.first(where: { $0.id == parts[2] }) {
                play(podcast: podcast, episode: episode)
            } else if let entry = DownloadCenter.shared.entry(itemId: parts[1], episodeId: parts[2]) {
                playDownloaded([entry], startAt: 0)
            }
        } else if parts[0] == "track", let item = await AppState.shared.item(parts[1]) {
            resumeBook(item: item)
        }
    }

    // MARK: Entry construction

    private func entry(podcast: LibraryItemExpanded, episode: PodcastEpisode) -> QueueEntry? {
        let local = DownloadCenter.shared.localUrl(itemId: podcast.id, episodeId: episode.id)
        guard let url = local ?? AppState.shared.client.streamUrl(itemId: podcast.id, episode: episode)
        else { return nil }
        return QueueEntry(
            kind: .episode(itemId: podcast.id, episodeId: episode.id),
            title: episode.title ?? "Episode",
            podcastTitle: podcast.title,
            url: url,
            coverUrl: AppState.shared.client.coverUrl(podcast.id),
            durationSec: episode.durationSec,
            publishedAt: episode.publishedAt
        )
    }

    private func entry(downloaded: DownloadedEpisode) -> QueueEntry? {
        guard let url = DownloadCenter.shared.localUrl(
            itemId: downloaded.itemId, episodeId: downloaded.episodeId
        ) else { return nil }
        return QueueEntry(
            kind: .episode(itemId: downloaded.itemId, episodeId: downloaded.episodeId),
            title: downloaded.title,
            podcastTitle: downloaded.podcastTitle,
            url: url,
            coverUrl: AppState.shared.client.coverUrl(downloaded.itemId),
            durationSec: downloaded.durationSec ?? 0,
            publishedAt: nil
        )
    }

    private func entry(item: LibraryItemExpanded, track: BookTrack) -> QueueEntry? {
        guard let url = AppState.shared.client.trackUrl(track) else { return nil }
        return QueueEntry(
            kind: .bookTrack(
                itemId: item.id, trackIndex: track.index ?? 0,
                startOffset: track.startOffsetSec, bookDuration: item.bookDuration
            ),
            title: track.title ?? "Part \((track.index ?? 0) + 1)",
            podcastTitle: item.title,
            url: url,
            coverUrl: AppState.shared.client.coverUrl(item.id),
            durationSec: track.durationSec,
            publishedAt: nil
        )
    }

    private func resolve(_ entry: PlaylistEntry) async -> QueueEntry? {
        if let downloaded = DownloadCenter.shared.entry(
            itemId: entry.itemId, episodeId: entry.episodeId
        ), let queueEntry = self.entry(downloaded: downloaded) {
            return queueEntry
        }
        guard let podcast = await AppState.shared.item(entry.itemId),
              let episode = podcast.episodes.first(where: { $0.id == entry.episodeId })
        else { return nil }
        return self.entry(podcast: podcast, episode: episode)
    }

    // MARK: Engine

    private func startQueue(_ entries: [QueueEntry], at index: Int, resumeFromServer: Bool = false, position: Double = 0) {
        queue = entries
        queueIndex = index
        var startPosition = position
        if resumeFromServer, let entry = entries.indices.contains(index) ? entries[index] : nil {
            startPosition = savedPosition(for: entry)
        }
        loadCurrent(startAt: startPosition, autoplay: true)
        startSyncLoop()
    }

    private func savedPosition(for entry: QueueEntry) -> Double {
        switch entry.kind {
        case .episode(let itemId, let episodeId):
            guard let progress = AppState.shared.progressFor(itemId: itemId, episodeId: episodeId),
                  !progress.finished
            else { return 0 }
            let t = progress.currentTime ?? 0
            return t > 1 ? t : 0
        case .bookTrack(let itemId, _, let startOffset, _):
            guard let progress = AppState.shared.bookProgress(itemId: itemId), !progress.finished
            else { return 0 }
            return max((progress.currentTime ?? 0) - startOffset, 0)
        }
    }

    private func loadCurrent(startAt: Double, autoplay: Bool) {
        guard let entry = current else { return }

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)

        teardownItemObservers()
        let item = AVPlayerItem(url: entry.url)
        if player == nil {
            player = AVPlayer()
        }
        player?.replaceCurrentItem(with: item)

        if startAt > 1 {
            player?.seek(to: CMTime(seconds: startAt, preferredTimescale: 1000))
            positionSec = startAt
        } else {
            positionSec = 0
        }

        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.itemFinished() }
        }
        statusObservation = player?.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            Task { @MainActor in
                self?.isBuffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            }
        }
        if timeObserver == nil {
            timeObserver = player?.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 0.5, preferredTimescale: 10),
                queue: .main
            ) { [weak self] time in
                Task { @MainActor in
                    guard let self, let player = self.player else { return }
                    self.positionSec = time.seconds
                    self.isPlaying = player.rate > 0
                    self.updateNowPlayingInfo()
                }
            }
        }

        if autoplay {
            player?.playImmediately(atRate: rate)
            isPlaying = true
        }
        updateNowPlayingInfo()
    }

    private func itemFinished() {
        // Push a final "finished" progress for the ended element.
        if let entry = current {
            Task { await pushProgress(entry: entry, position: entry.durationSec) }
        }
        if queueIndex + 1 < queue.count {
            queueIndex += 1
            loadCurrent(startAt: savedPosition(for: queue[queueIndex]), autoplay: true)
        } else {
            isPlaying = false
        }
    }

    func stop() {
        syncTask?.cancel()
        teardownItemObservers()
        if let observer = timeObserver { player?.removeTimeObserver(observer) }
        timeObserver = nil
        player?.pause()
        player = nil
        queue = []
        queueIndex = 0
        isPlaying = false
        positionSec = 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    private func teardownItemObservers() {
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        statusObservation?.invalidate()
        statusObservation = nil
    }

    // MARK: Transport

    func togglePlayPause() {
        guard let player else { return }
        if player.rate > 0 {
            player.pause()
            isPlaying = false
            if let entry = current {
                Task { await pushProgress(entry: entry, position: positionSec) }
            }
        } else {
            player.playImmediately(atRate: rate)
            isPlaying = true
        }
        updateNowPlayingInfo()
    }

    func skip(_ seconds: Double) {
        guard let player else { return }
        let target = max(0, positionSec + seconds)
        player.seek(to: CMTime(seconds: target, preferredTimescale: 1000))
        positionSec = target
        updateNowPlayingInfo()
    }

    func seek(to seconds: Double) {
        player?.seek(to: CMTime(seconds: seconds, preferredTimescale: 1000))
        positionSec = seconds
        updateNowPlayingInfo()
    }

    func setRate(_ newRate: Float) {
        rate = newRate
        if isPlaying { player?.rate = newRate }
        updateNowPlayingInfo()
    }

    // MARK: Progress sync (pause + every 15s, like Android)

    private func startSyncLoop() {
        syncTask?.cancel()
        syncTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard let self else { return }
                if self.isPlaying, let entry = self.current {
                    await self.pushProgress(entry: entry, position: self.positionSec)
                }
            }
        }
    }

    private func pushProgress(entry: QueueEntry, position: Double) async {
        guard position > 0 else { return }
        switch entry.kind {
        case .episode(let itemId, let episodeId):
            await AppState.shared.pushProgress(
                itemId: itemId, episodeId: episodeId,
                currentTime: position, duration: entry.durationSec
            )
        case .bookTrack(let itemId, _, let startOffset, let bookDuration):
            await AppState.shared.pushProgress(
                itemId: itemId, episodeId: nil,
                currentTime: startOffset + position, duration: bookDuration
            )
        }
        Settings.lastPlayedMediaId = entry.mediaId
        Settings.lastPlayedPositionSec = position
    }

    // MARK: Lock screen / CarPlay metadata and commands

    private func updateNowPlayingInfo() {
        guard let now = current else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: now.title,
            MPMediaItemPropertyArtist: now.podcastTitle,
            MPMediaItemPropertyPlaybackDuration: now.durationSec,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: positionSec,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let (url, artwork) = artworkCache, url == now.coverUrl {
            info[MPMediaItemPropertyArtwork] = artwork
        } else if let coverUrl = now.coverUrl {
            Task { [weak self] in
                guard let (data, _) = try? await URLSession.shared.data(from: coverUrl),
                      let image = UIImage(data: data) else { return }
                let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                await MainActor.run {
                    self?.artworkCache = (coverUrl, artwork)
                    self?.updateNowPlayingInfo()
                }
            }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                if self?.isPlaying == false { self?.togglePlayPause() }
            }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                if self?.isPlaying == true { self?.togglePlayPause() }
            }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayPause() }
            return .success
        }
        center.skipBackwardCommand.isEnabled = true
        center.skipBackwardCommand.preferredIntervals = [10]
        center.skipBackwardCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.skip(-10) }
            return .success
        }
        center.skipForwardCommand.isEnabled = true
        center.skipForwardCommand.preferredIntervals = [30]
        center.skipForwardCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.skip(30) }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            if let e = event as? MPChangePlaybackPositionCommandEvent {
                Task { @MainActor in self?.seek(to: e.positionTime) }
                return .success
            }
            return .commandFailed
        }
        center.changePlaybackRateCommand.isEnabled = true
        center.changePlaybackRateCommand.supportedPlaybackRates = [0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0]
        center.changePlaybackRateCommand.addTarget { [weak self] event in
            if let e = event as? MPChangePlaybackRateCommandEvent {
                Task { @MainActor in self?.setRate(e.playbackRate) }
                return .success
            }
            return .commandFailed
        }
    }
}
