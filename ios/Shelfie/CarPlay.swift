import CarPlay
import Foundation

/**
 CarPlay audio app: Continue Listening + Podcasts tabs, playback through the
 shared PlayerManager (so the phone and car stay in sync). Requires the
 com.apple.developer.carplay-audio entitlement to appear in the car.
 */
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    var interfaceController: CPInterfaceController?
    private let continueTemplate = CPListTemplate(title: "Continue", sections: [])
    private let podcastsTemplate = CPListTemplate(title: "Podcasts", sections: [])

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        continueTemplate.tabImage = UIImage(systemName: "play.circle")
        podcastsTemplate.tabImage = UIImage(systemName: "square.grid.2x2")
        let tabBar = CPTabBarTemplate(templates: [continueTemplate, podcastsTemplate])
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

        let continueRows = await AppState.shared.continueListening()
        let continueItems = continueRows.map { row in
            let item = CPListItem(text: row.episode.title ?? "Episode", detailText: row.podcast.title)
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    PlayerManager.shared.play(podcast: row.podcast, episode: row.episode)
                    self?.pushNowPlaying()
                    completion()
                }
            }
            return item
        }
        continueTemplate.updateSections([CPListSection(items: continueItems)])

        let podcastItems = AppState.shared.podcasts.map { summary in
            let item = CPListItem(text: summary.title, detailText: nil)
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    await self?.showEpisodes(itemId: summary.id)
                    completion()
                }
            }
            return item
        }
        podcastsTemplate.updateSections([CPListSection(items: podcastItems)])
    }

    @MainActor
    private func showEpisodes(itemId: String) async {
        guard let podcast = try? await AppState.shared.client.item(itemId) else { return }
        let episodes = (podcast.media?.episodes ?? [])
            .sorted { ($0.publishedAt ?? 0) > ($1.publishedAt ?? 0) }
        let items = episodes.prefix(60).map { episode in
            let item = CPListItem(
                text: episode.title ?? "Episode",
                detailText: formatDuration(episode.durationSec)
            )
            item.handler = { [weak self] _, completion in
                Task { @MainActor in
                    PlayerManager.shared.play(podcast: podcast, episode: episode)
                    self?.pushNowPlaying()
                    completion()
                }
            }
            return item
        }
        let list = CPListTemplate(title: podcast.title, sections: [CPListSection(items: Array(items))])
        interfaceController?.pushTemplate(list, animated: true, completion: nil)
    }

    @MainActor
    private func pushNowPlaying() {
        guard let interfaceController else { return }
        if interfaceController.topTemplate != CPNowPlayingTemplate.shared {
            interfaceController.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
        }
    }
}
