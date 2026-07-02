import CarPlay
import Foundation
import UIKit

/**
 CarPlay audio app mirroring the Android Auto tree: Continue Listening,
 Podcasts, Playlists, and Downloads tabs, with completion checkmarks and
 playback through the shared PlayerManager. The Downloads tab is built from
 the local index so it works fully offline.
 */
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    var interfaceController: CPInterfaceController?

    private let continueTemplate = CPListTemplate(title: "Continue", sections: [])
    private let podcastsTemplate = CPListTemplate(title: "Podcasts", sections: [])
    private let playlistsTemplate = CPListTemplate(title: "Playlists", sections: [])
    private let downloadsTemplate = CPListTemplate(title: "Downloads", sections: [])

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        continueTemplate.tabImage = UIImage(systemName: "play.circle")
        podcastsTemplate.tabImage = UIImage(systemName: "square.grid.2x2")
        playlistsTemplate.tabImage = UIImage(systemName: "list.bullet")
        downloadsTemplate.tabImage = UIImage(systemName: "arrow.down.circle")
        let tabBar = CPTabBarTemplate(templates: [
            continueTemplate, podcastsTemplate, playlistsTemplate, downloadsTemplate,
        ])
        interfaceController.setRootTemplate(tabBar, animated: false, completion: nil)
        Task { await loadContent() }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        self.interfaceController = nil
    }

    @MainActor
    private func loadContent() async {
        await AppState.shared.refresh()
        await reloadContinue()
        reloadPodcasts()
        reloadPlaylists()
        reloadDownloads()
    }

    // MARK: Continue Listening

    @MainActor
    private func reloadContinue() async {
        let rows = await AppState.shared.continueListening()
        let items = rows.map { row in
            let item = CPListItem(
                text: row.episode.title ?? "Episode",
                detailText: row.podcast.title
            )
            item.playbackProgress = CGFloat(row.progress)
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    PlayerManager.shared.play(podcast: row.podcast, episode: row.episode)
                    self?.pushNowPlaying()
                    completion()
                }
            }
            return item
        }
        continueTemplate.updateSections([CPListSection(items: items)])
    }

    // MARK: Podcasts

    @MainActor
    private func reloadPodcasts() {
        let items = AppState.shared.podcasts.map { summary in
            let item = CPListItem(text: summary.title, detailText: summary.author)
            item.accessoryType = .disclosureIndicator
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    await self?.showEpisodes(itemId: summary.id)
                    completion()
                }
            }
            return item
        }
        podcastsTemplate.updateSections([CPListSection(items: items)])
    }

    @MainActor
    private func showEpisodes(itemId: String) async {
        guard let podcast = await AppState.shared.item(itemId) else { return }
        await AppState.shared.refreshProgress()

        if podcast.isBook {
            let items = podcast.tracks
                .sorted { ($0.index ?? 0) < ($1.index ?? 0) }
                .map { track in
                    let item = CPListItem(
                        text: track.title ?? "Part \((track.index ?? 0) + 1)",
                        detailText: formatDuration(track.durationSec)
                    )
                    item.handler = { [weak self] _, completion in
                        Task { @MainActor in
                            PlayerManager.shared.playBook(item: podcast, trackIndex: track.index ?? 0)
                            self?.pushNowPlaying()
                            completion()
                        }
                    }
                    return item
                }
            let list = CPListTemplate(title: podcast.title, sections: [CPListSection(items: items)])
            interfaceController?.pushTemplate(list, animated: true, completion: nil)
            return
        }

        let episodes = podcast.episodes.sorted { ($0.publishedAt ?? 0) > ($1.publishedAt ?? 0) }
        let items = episodes.prefix(60).map { episode in
            episodeItem(podcast: podcast, episode: episode)
        }
        let list = CPListTemplate(title: podcast.title, sections: [CPListSection(items: Array(items))])
        interfaceController?.pushTemplate(list, animated: true, completion: nil)
    }

    // MARK: Playlists

    @MainActor
    private func reloadPlaylists() {
        let items = PlaylistStore.shared.playlists.map { playlist in
            let item = CPListItem(
                text: playlist.name,
                detailText: "\(playlist.entries.count) episodes"
            )
            item.accessoryType = .disclosureIndicator
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    self?.showPlaylist(playlist)
                    completion()
                }
            }
            return item
        }
        playlistsTemplate.updateSections([CPListSection(items: items)])
    }

    @MainActor
    private func showPlaylist(_ playlist: Playlist) {
        let items = playlist.entries.enumerated().map { index, entry in
            let item = CPListItem(text: entry.title, detailText: entry.podcastTitle)
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    await PlayerManager.shared.playPlaylist(playlist.entries, startAt: index)
                    self?.pushNowPlaying()
                    completion()
                }
            }
            return item
        }
        let list = CPListTemplate(title: playlist.name, sections: [CPListSection(items: items)])
        interfaceController?.pushTemplate(list, animated: true, completion: nil)
    }

    // MARK: Downloads (offline-capable)

    @MainActor
    private func reloadDownloads() {
        let list = DownloadCenter.shared.downloaded.sorted { $0.downloadedAt > $1.downloadedAt }
        let items = list.enumerated().map { index, entry in
            let item = CPListItem(text: entry.title, detailText: entry.podcastTitle)
            let progress = AppState.shared.progressFor(
                itemId: entry.itemId, episodeId: entry.episodeId
            )
            if progress?.finished == true {
                item.accessoryImage = UIImage(systemName: "checkmark.circle")
            } else if let fraction = progress?.progress, fraction > 0.01 {
                item.playbackProgress = CGFloat(min(fraction, 1))
            }
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    PlayerManager.shared.playDownloaded(list, startAt: index)
                    self?.pushNowPlaying()
                    completion()
                }
            }
            return item
        }
        downloadsTemplate.updateSections([CPListSection(items: items)])
    }

    // MARK: Helpers

    @MainActor
    private func episodeItem(podcast: LibraryItemExpanded, episode: PodcastEpisode) -> CPListItem {
        let item = CPListItem(
            text: episode.title ?? "Episode",
            detailText: formatDuration(episode.durationSec)
        )
        let progress = AppState.shared.progressFor(itemId: podcast.id, episodeId: episode.id)
        if progress?.finished == true {
            item.accessoryImage = UIImage(systemName: "checkmark.circle")
        } else if let fraction = progress?.progress, fraction > 0.01 {
            item.playbackProgress = CGFloat(min(fraction, 1))
        }
        item.isPlaying = PlayerManager.shared.current?.mediaId == "episode:\(podcast.id):\(episode.id)"
        item.handler = { [weak self] _, completion in
            Task { @MainActor in
                PlayerManager.shared.play(podcast: podcast, episode: episode)
                self?.pushNowPlaying()
                completion()
            }
        }
        return item
    }

    @MainActor
    private func pushNowPlaying() {
        guard let interfaceController else { return }
        if interfaceController.topTemplate != CPNowPlayingTemplate.shared {
            interfaceController.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
        }
    }
}
