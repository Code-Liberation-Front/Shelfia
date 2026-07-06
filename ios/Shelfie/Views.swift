import SwiftUI

// MARK: - Root tabs (Home / Latest / Library / Playlist, like Android)

struct MainView: View {
    @EnvironmentObject var state: AppState
    @StateObject private var router = Router.shared

    var body: some View {
        TabView(selection: $router.tab) {
            NavigationStack { rootScreen { HomeView() } }
                .withMiniPlayer()
                .tabItem { Label("Home", systemImage: "house") }
                .tag(0)
            NavigationStack { rootScreen { LatestView() } }
                .withMiniPlayer()
                .tabItem { Label("Latest", systemImage: "clock") }
                .tag(1)
            NavigationStack(path: $router.libraryPath) { rootScreen { LibraryView() } }
                .withMiniPlayer()
                .tabItem { Label("Library", systemImage: "square.grid.2x2") }
                .tag(2)
            NavigationStack { PlaylistScreen().withRootToolbar() }
                .withMiniPlayer()
                .tabItem { Label("Playlist", systemImage: "list.bullet") }
                .tag(3)
        }
        .environmentObject(router)
        .overlay(alignment: .top) {
            if !state.isOnline { OfflineBanner() }
        }
        .task { await state.refresh() }
    }

    @ViewBuilder
    private func rootScreen<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        Group {
            if state.isOnline {
                content()
            } else {
                OfflineTabHint()
            }
        }
        .overlay(alignment: .top) {
            if state.isRefreshing { TopLoadingBar() }
        }
        .withRootToolbar()
    }
}

private struct RootToolbar: ViewModifier {
    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    NavigationLink { SearchView() } label: { Image(systemName: "magnifyingglass") }
                    NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }
                }
            }
            .navigationDestination(for: String.self) { itemId in
                PodcastDetailView(itemId: itemId)
            }
    }
}

private extension View {
    func withRootToolbar() -> some View { modifier(RootToolbar()) }

    /**
     Pins the mini player inside the tab's content, so it floats above the
     tab bar instead of covering it. MiniPlayerBar renders nothing while
     idle, so this is safe to attach unconditionally.
     */
    func withMiniPlayer() -> some View {
        safeAreaInset(edge: .bottom) { MiniPlayerBar() }
    }
}

// MARK: - Home (Continue Listening + Recently Added carousels)

struct HomeView: View {
    @EnvironmentObject var state: AppState
    @State private var continueRows: [AppState.InProgressEpisode] = []
    @State private var playlistPickerFor: PlaylistEntry?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if !continueRows.isEmpty {
                    Text("Continue Listening").font(.title3.bold()).padding(.horizontal, 16)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 12) {
                            ForEach(continueRows) { row in
                                ContinueCard(row: row, playlistPickerFor: $playlistPickerFor)
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }

                let recent = state.recentlyAdded()
                if !recent.isEmpty {
                    Text("Recently Added").font(.title3.bold()).padding(.horizontal, 16)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 12) {
                            ForEach(recent) { podcast in
                                NavigationLink(value: podcast.id) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        CoverImage(url: state.client.coverUrl(podcast.id))
                                            .frame(width: 130, height: 130)
                                        Text(podcast.title)
                                            .font(.footnote.weight(.medium))
                                            .lineLimit(2)
                                            .multilineTextAlignment(.leading)
                                            .foregroundStyle(.primary)
                                    }
                                    .frame(width: 130)
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }

            }
            .padding(.vertical, 12)
        }
        .navigationTitle("Shelfia")
        .refreshable {
            await state.refresh(force: true)
            continueRows = await state.continueListening(force: true)
        }
        .task(id: state.progressRevision) {
            // Cached rows render instantly; the network result replaces them.
            if continueRows.isEmpty {
                continueRows = state.continueListeningCached()
            }
            continueRows = await state.continueListening()
        }
        .sheet(item: $playlistPickerFor) { entry in
            PlaylistPickerSheet(entries: [entry])
        }
    }
}

private struct ContinueCard: View {
    @EnvironmentObject var state: AppState
    let row: AppState.InProgressEpisode
    @Binding var playlistPickerFor: PlaylistEntry?

    var body: some View {
        Button {
            PlayerManager.shared.play(podcast: row.podcast, episode: row.episode)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                CoverImage(
                    url: state.client.coverUrl(row.podcast.id),
                    finished: row.progress >= 0.99
                )
                .frame(width: 150, height: 150)
                ProgressView(value: row.progress).tint(.accentColor)
                Text(row.episode.title ?? "Episode")
                    .font(.footnote.weight(.medium))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .foregroundStyle(.primary)
                Text(row.podcast.title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if let date = episodeDate(row.episode) {
                    Text(date, style: .date).font(.caption2).foregroundStyle(.secondary)
                }
            }
            .frame(width: 150)
        }
        .episodeMenu(
            itemId: row.podcast.id,
            episode: row.episode,
            podcastTitle: row.podcast.title,
            playlistPickerFor: $playlistPickerFor
        )
    }
}

// MARK: - Latest (newest episodes across the library)

struct LatestView: View {
    @EnvironmentObject var state: AppState
    @ObservedObject private var downloads = DownloadCenter.shared
    @State private var playlistPickerFor: PlaylistEntry?
    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var bulkPicker = false

    var body: some View {
        List {
            if !state.activeLibraryIsPodcast {
                Text("Latest episodes are only available for podcast libraries.")
                    .foregroundStyle(.secondary)
            }
            ForEach(state.latest) { episode in
                let itemId = episode.libraryItemId ?? ""
                let podcastTitle = state.podcasts.first { $0.id == itemId }?.title
                row(episode: episode, itemId: itemId, podcastTitle: podcastTitle)
            }
        }
        .listStyle(.plain)
        .navigationTitle("Latest")
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                if !state.latest.isEmpty {
                    Button(selecting ? "Cancel" : "Select") {
                        selecting.toggle()
                        selection = []
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if selecting {
                SelectionBar(
                    selectedCount: selection.count,
                    totalCount: state.latest.count,
                    onSelectAll: { selection = Set(state.latest.map(\.id)) },
                    onSelectNone: { selection = [] },
                    onDownload: { bulkDownload() },
                    onAddToPlaylist: { bulkPicker = true },
                    onDone: { selecting = false; selection = [] }
                )
            }
        }
        .refreshable { await state.refreshLatest() }
        .task {
            if state.latest.isEmpty { await state.refreshLatest() }
            await state.refreshProgress()
        }
        .sheet(item: $playlistPickerFor) { entry in
            PlaylistPickerSheet(entries: [entry])
        }
        .sheet(isPresented: $bulkPicker) {
            PlaylistPickerSheet(entries: selectedEntries())
        }
    }

    @ViewBuilder
    private func row(episode: PodcastEpisode, itemId: String, podcastTitle: String?) -> some View {
        Button {
            if selecting {
                if selection.contains(episode.id) {
                    selection.remove(episode.id)
                } else {
                    selection.insert(episode.id)
                }
            } else {
                Task {
                    if let podcast = await state.item(itemId) {
                        PlayerManager.shared.play(podcast: podcast, episode: episode)
                    }
                }
            }
        } label: {
            HStack(spacing: 10) {
                if selecting {
                    Image(systemName: selection.contains(episode.id)
                        ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(.tint)
                }
                EpisodeRowContent(itemId: itemId, episode: episode, podcastTitle: podcastTitle)
            }
        }
        .episodeMenu(
            itemId: itemId,
            episode: episode,
            podcastTitle: podcastTitle ?? "",
            playlistPickerFor: $playlistPickerFor
        )
    }

    private func selectedEntries() -> [PlaylistEntry] {
        state.latest.filter { selection.contains($0.id) }.map { episode in
            PlaylistEntry(
                itemId: episode.libraryItemId ?? "",
                episodeId: episode.id,
                title: episode.title ?? "Episode",
                podcastTitle: state.podcasts.first { $0.id == episode.libraryItemId }?.title ?? ""
            )
        }
    }

    private func bulkDownload() {
        let picked = state.latest.filter { selection.contains($0.id) }
        Task {
            for episode in picked {
                if let itemId = episode.libraryItemId, let podcast = await state.item(itemId) {
                    downloads.start(podcast: podcast, episode: episode)
                }
            }
        }
        selecting = false
        selection = []
    }
}

// MARK: - Library grid

struct LibraryView: View {
    @EnvironmentObject var state: AppState
    private let columns = [GridItem(.adaptive(minimum: 140), spacing: 12)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 16) {
                ForEach(state.podcasts) { podcast in
                    NavigationLink(value: podcast.id) {
                        VStack(alignment: .leading, spacing: 6) {
                            CoverImage(url: state.client.coverUrl(podcast.id))
                            Text(podcast.title)
                                .font(.footnote.weight(.medium))
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                                .foregroundStyle(.primary)
                            if podcast.numEpisodes > 0 {
                                Text("\(podcast.numEpisodes) episodes")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .padding(12)
        }
        .navigationTitle(state.activeLibrary?.name ?? "Library")
        .refreshable { await state.refresh(force: true) }
    }
}

// MARK: - Podcast / audiobook detail

struct PodcastDetailView: View {
    @EnvironmentObject var state: AppState
    @ObservedObject private var downloads = DownloadCenter.shared
    let itemId: String

    @State private var item: LibraryItemExpanded?
    @State private var playlistPickerFor: PlaylistEntry?
    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var bulkPicker = false

    private var episodes: [PodcastEpisode] {
        (item?.episodes ?? []).sorted { ($0.publishedAt ?? 0) > ($1.publishedAt ?? 0) }
    }

    var body: some View {
        List {
            if let item {
                header(item)
                if item.isBook {
                    bookSection(item)
                } else {
                    ForEach(episodes) { episode in
                        episodeRow(item: item, episode: episode)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .listStyle(.plain)
        .navigationTitle(item?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                if item?.isBook == false && !episodes.isEmpty {
                    Button(selecting ? "Cancel" : "Select") {
                        selecting.toggle()
                        selection = []
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if selecting {
                SelectionBar(
                    selectedCount: selection.count,
                    totalCount: episodes.count,
                    onSelectAll: { selection = Set(episodes.map(\.id)) },
                    onSelectNone: { selection = [] },
                    onDownload: { bulkDownload() },
                    onAddToPlaylist: { bulkPicker = true },
                    onDone: { selecting = false; selection = [] }
                )
            }
        }
        .task {
            // Cached detail renders instantly; the network result replaces it.
            if item == nil {
                item = state.cachedItem(itemId)
            }
            item = await state.item(itemId)
            await state.refreshProgress()
        }
        .refreshable {
            item = await state.item(itemId, force: true)
            await state.refreshProgress(force: true)
        }
        .sheet(item: $playlistPickerFor) { entry in
            PlaylistPickerSheet(entries: [entry])
        }
        .sheet(isPresented: $bulkPicker) {
            PlaylistPickerSheet(entries: selectedEntries())
        }
    }

    private func header(_ item: LibraryItemExpanded) -> some View {
        HStack(alignment: .top, spacing: 14) {
            CoverImage(url: state.client.coverUrl(item.id))
                .frame(width: 110, height: 110)
            VStack(alignment: .leading, spacing: 6) {
                Text(item.title).font(.headline)
                if let author = item.author {
                    Text(author).font(.subheadline).foregroundStyle(.secondary)
                }
                Text(item.isBook
                    ? "\(item.tracks.count) parts"
                    : "\(item.episodes.count) episodes")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .listRowSeparator(.hidden)
    }

    @ViewBuilder
    private func episodeRow(item: LibraryItemExpanded, episode: PodcastEpisode) -> some View {
        Button {
            if selecting {
                if selection.contains(episode.id) {
                    selection.remove(episode.id)
                } else {
                    selection.insert(episode.id)
                }
            } else {
                PlayerManager.shared.play(podcast: item, episode: episode)
            }
        } label: {
            HStack(spacing: 10) {
                if selecting {
                    Image(systemName: selection.contains(episode.id)
                        ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(.tint)
                }
                EpisodeRowContent(itemId: item.id, episode: episode, showCover: false)
            }
        }
        .episodeMenu(
            itemId: item.id,
            episode: episode,
            podcastTitle: item.title,
            showGoToPodcast: false,
            playlistPickerFor: $playlistPickerFor
        )
    }

    @ViewBuilder
    private func bookSection(_ item: LibraryItemExpanded) -> some View {
        let progress = state.bookProgress(itemId: item.id)
        if let progress, (progress.currentTime ?? 0) > 0, !progress.finished {
            Button {
                PlayerManager.shared.resumeBook(item: item)
            } label: {
                Label(
                    "Resume · \(formatDuration(progress.currentTime ?? 0)) of \(formatDuration(item.bookDuration))",
                    systemImage: "play.circle.fill"
                )
            }
        }
        ForEach(item.tracks.sorted { ($0.index ?? 0) < ($1.index ?? 0) }) { track in
            TrackRow(item: item, track: track)
        }
    }

    private func selectedEntries() -> [PlaylistEntry] {
        guard let item else { return [] }
        return episodes.filter { selection.contains($0.id) }.map {
            PlaylistEntry(
                itemId: item.id, episodeId: $0.id,
                title: $0.title ?? "Episode", podcastTitle: item.title
            )
        }
    }

    private func bulkDownload() {
        guard let item else { return }
        for episode in episodes where selection.contains(episode.id) {
            downloads.start(podcast: item, episode: episode)
        }
        selecting = false
        selection = []
    }
}

private struct TrackRow: View {
    @EnvironmentObject var state: AppState
    @ObservedObject private var player = PlayerManager.shared
    let item: LibraryItemExpanded
    let track: BookTrack

    var body: some View {
        let isCurrent = player.current?.mediaId == "track:\(item.id):\(track.index ?? 0)"
        Button {
            PlayerManager.shared.playBook(item: item, trackIndex: track.index ?? 0)
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(track.title ?? "Part \((track.index ?? 0) + 1)")
                        .font(.subheadline.weight(.medium))
                    Text(formatDuration(track.durationSec))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: isCurrent && player.isPlaying ? "pause.circle" : "play.circle")
                    .font(.title3)
                    .foregroundStyle(.tint)
            }
        }
        .contextMenu {
            Button {
                Task {
                    await state.resetProgress(itemId: item.id, episodeId: nil, duration: item.bookDuration)
                }
            } label: {
                Label("Reset listen time", systemImage: "arrow.counterclockwise")
            }
            Button {
                Task {
                    let finished = state.bookProgress(itemId: item.id)?.finished == true
                    await state.setFinished(
                        itemId: item.id, episodeId: nil,
                        finished: !finished, duration: item.bookDuration
                    )
                }
            } label: {
                Label(
                    state.bookProgress(itemId: item.id)?.finished == true
                        ? "Mark as unplayed" : "Mark as finished",
                    systemImage: "checkmark.circle"
                )
            }
        }
    }
}

// MARK: - Search (client-side, debounced, like Android)

struct SearchView: View {
    @EnvironmentObject var state: AppState
    @State private var query = ""
    @State private var podcastResults: [LibraryItemSummary] = []
    @State private var episodeResults: [(LibraryItemExpanded, PodcastEpisode)] = []
    @State private var searching = false
    @State private var playlistPickerFor: PlaylistEntry?
    @State private var debounce: Task<Void, Never>?

    var body: some View {
        List {
            if searching {
                ProgressView().frame(maxWidth: .infinity)
            }
            if !podcastResults.isEmpty {
                Section("Podcasts") {
                    ForEach(podcastResults) { podcast in
                        NavigationLink(value: podcast.id) {
                            HStack(spacing: 12) {
                                CoverImage(url: state.client.coverUrl(podcast.id))
                                    .frame(width: 44, height: 44)
                                VStack(alignment: .leading) {
                                    Text(podcast.title).font(.subheadline.weight(.medium))
                                    if let author = podcast.author {
                                        Text(author).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if !episodeResults.isEmpty {
                Section("Episodes") {
                    ForEach(episodeResults, id: \.1.id) { podcast, episode in
                        Button {
                            PlayerManager.shared.play(podcast: podcast, episode: episode)
                        } label: {
                            EpisodeRowContent(
                                itemId: podcast.id, episode: episode,
                                podcastTitle: podcast.title
                            )
                        }
                        .episodeMenu(
                            itemId: podcast.id,
                            episode: episode,
                            podcastTitle: podcast.title,
                            playlistPickerFor: $playlistPickerFor
                        )
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Search")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always))
        .onChange(of: query) { newValue in
            debounce?.cancel()
            debounce = Task {
                try? await Task.sleep(nanoseconds: 400_000_000)
                guard !Task.isCancelled else { return }
                searching = true
                let results = await state.search(newValue)
                podcastResults = results.podcasts
                episodeResults = results.episodes
                searching = false
            }
        }
        .sheet(item: $playlistPickerFor) { entry in
            PlaylistPickerSheet(entries: [entry])
        }
    }
}
