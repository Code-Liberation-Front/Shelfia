import AVFoundation
import Foundation
import MediaPlayer

struct NowPlaying: Equatable {
    let itemId: String
    let episodeId: String
    let title: String
    let podcastTitle: String
    let streamUrl: URL
    let coverUrl: URL?
    let durationSec: Double
}

/** Single AVPlayer shared by the phone UI and CarPlay, with Overcast-style
 10s back / 30s forward skips and progress sync back to Audiobookshelf. */
@MainActor
final class PlayerManager: ObservableObject {
    static let shared = PlayerManager()

    @Published var current: NowPlaying?
    @Published var isPlaying = false
    @Published var positionSec: Double = 0
    @Published var rate: Float = 1.0

    private var player: AVPlayer?
    private var timeObserver: Any?
    private var syncTask: Task<Void, Never>?

    private init() {
        setupRemoteCommands()
    }

    func play(podcast: ItemExpanded, episode: Episode) {
        guard let url = AppState.shared.client.streamUrl(episode) else { return }
        let now = NowPlaying(
            itemId: podcast.id,
            episodeId: episode.id,
            title: episode.title ?? "Episode",
            podcastTitle: podcast.title,
            streamUrl: url,
            coverUrl: AppState.shared.client.coverUrl(podcast.id),
            durationSec: episode.durationSec
        )
        if current == now {
            togglePlayPause()
            return
        }
        current = now

        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)

        if let observer = timeObserver { player?.removeTimeObserver(observer) }
        let item = AVPlayerItem(url: url)
        let player = AVPlayer(playerItem: item)
        self.player = player

        // Resume from server-side progress when available and unfinished.
        if let progress = AppState.shared.progressFor(itemId: now.itemId, episodeId: now.episodeId),
           progress.isFinished != true, let t = progress.currentTime, t > 1 {
            player.seek(to: CMTime(seconds: t, preferredTimescale: 1000))
        }

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1, preferredTimescale: 10),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                self.positionSec = time.seconds
                self.isPlaying = player.rate > 0
                self.updateNowPlayingInfo()
            }
        }

        player.play()
        player.rate = rate
        isPlaying = true
        updateNowPlayingInfo()
        startSyncLoop()
    }

    func togglePlayPause() {
        guard let player else { return }
        if player.rate > 0 {
            player.pause()
            isPlaying = false
            Task { await pushProgress() }
        } else {
            player.play()
            player.rate = rate
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

    private func startSyncLoop() {
        syncTask?.cancel()
        syncTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                await self?.pushProgress()
            }
        }
    }

    private func pushProgress() async {
        guard let now = current, positionSec > 0 else { return }
        try? await AppState.shared.client.updateProgress(
            itemId: now.itemId,
            episodeId: now.episodeId,
            currentTime: positionSec,
            duration: now.durationSec
        )
    }

    // MARK: Lock screen / CarPlay now-playing metadata and commands

    private func updateNowPlayingInfo() {
        guard let now = current else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: now.title,
            MPMediaItemPropertyArtist: now.podcastTitle,
            MPMediaItemPropertyPlaybackDuration: now.durationSec,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: positionSec,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0.0,
        ]
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayPause() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
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
    }
}
