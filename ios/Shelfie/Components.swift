import SwiftUI

// MARK: - Cross-tab navigation (menu "Go to podcast" from any screen)

@MainActor
final class Router: ObservableObject {
    static let shared = Router()
    @Published var tab: Int = 0
    @Published var libraryPath = NavigationPath()

    func goToPodcast(_ itemId: String) {
        tab = 2
        libraryPath.append(itemId)
    }
}

// MARK: - Cover image with completion treatment (blur + check when finished)

struct CoverImage: View {
    let url: URL?
    var finished = false

    var body: some View {
        AsyncImage(url: url) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else {
                ZStack {
                    Color(white: 0.16)
                    Image(systemName: "book.closed").foregroundStyle(.secondary)
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .overlay {
            if finished {
                ZStack {
                    Rectangle().fill(.black.opacity(0.5))
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.white)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - Episode row (Android Components.EpisodeRowContent parity)

struct EpisodeRowContent: View {
    @EnvironmentObject var state: AppState
    @ObservedObject var downloads = DownloadCenter.shared
    @ObservedObject var player = PlayerManager.shared

    let itemId: String
    let episode: PodcastEpisode
    var podcastTitle: String?
    var showCover = true

    var body: some View {
        let progress = state.progressFor(itemId: itemId, episodeId: episode.id)
        let finished = progress?.finished == true
        let isCurrent = player.current?.mediaId == "episode:\(itemId):\(episode.id)"

        HStack(spacing: 12) {
            if showCover {
                CoverImage(url: state.client.coverUrl(itemId), finished: finished)
                    .frame(width: 52, height: 52)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(episode.title ?? "Episode")
                    .font(.subheadline.weight(.medium))
                    .lineLimit(2)
                    .foregroundStyle(finished ? .secondary : .primary)
                if let podcastTitle {
                    Text(podcastTitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                HStack(spacing: 6) {
                    if let date = episodeDate(episode) {
                        Text(date, style: .date)
                    }
                    if episode.durationSec > 0 {
                        Text("•")
                        Text(formatDuration(episode.durationSec))
                    }
                    downloadStateChip
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                if let fraction = progress?.progress, fraction > 0.01, !finished {
                    ProgressView(value: min(fraction, 1)).tint(.accentColor)
                }
            }
            Spacer(minLength: 4)
            if finished {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.secondary)
            } else {
                Image(systemName: isCurrent && player.isPlaying ? "pause.circle" : "play.circle")
                    .font(.title3)
                    .foregroundStyle(.tint)
            }
        }
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var downloadStateChip: some View {
        if let active = downloads.active["\(itemId):\(episode.id)"] {
            if active.failed {
                Text("Download failed").foregroundStyle(.red)
            } else if let fraction = active.fraction {
                Text("Downloading \(Int(fraction * 100))%").foregroundStyle(.tint)
            } else {
                Text("Downloading…").foregroundStyle(.tint)
            }
        } else if downloads.isDownloaded(itemId: itemId, episodeId: episode.id) {
            Image(systemName: "arrow.down.circle.fill").foregroundStyle(.tint)
        }
    }
}

// MARK: - Episode long-press menu (reset / mark / playlist / go to podcast / download)

struct EpisodeMenu: ViewModifier {
    @EnvironmentObject var state: AppState
    @ObservedObject var downloads = DownloadCenter.shared

    let itemId: String
    let episode: PodcastEpisode
    let podcastTitle: String
    var showGoToPodcast = true
    var extraRemove: (() -> Void)?
    @Binding var playlistPickerFor: PlaylistEntry?

    func body(content: Content) -> some View {
        let finished = state.progressFor(itemId: itemId, episodeId: episode.id)?.finished == true
        content.contextMenu {
            Button {
                Task { await state.resetProgress(itemId: itemId, episodeId: episode.id, duration: episode.durationSec) }
            } label: {
                Label("Reset listen time", systemImage: "arrow.counterclockwise")
            }
            Button {
                Task {
                    await state.setFinished(
                        itemId: itemId, episodeId: episode.id,
                        finished: !finished, duration: episode.durationSec
                    )
                }
            } label: {
                Label(finished ? "Mark as unplayed" : "Mark as finished",
                      systemImage: finished ? "circle" : "checkmark.circle")
            }
            Button {
                playlistPickerFor = PlaylistEntry(
                    itemId: itemId, episodeId: episode.id,
                    title: episode.title ?? "Episode", podcastTitle: podcastTitle
                )
            } label: {
                Label("Add to playlist", systemImage: "text.badge.plus")
            }
            if showGoToPodcast {
                Button {
                    Router.shared.goToPodcast(itemId)
                } label: {
                    Label("Go to podcast", systemImage: "square.grid.2x2")
                }
            }
            downloadButton
            if let extraRemove {
                Button(role: .destructive, action: extraRemove) {
                    Label("Remove from playlist", systemImage: "minus.circle")
                }
            }
        }
    }

    @ViewBuilder
    private var downloadButton: some View {
        if downloads.isDownloading(itemId: itemId, episodeId: episode.id) {
            Button {
                downloads.cancel(itemId: itemId, episodeId: episode.id)
            } label: {
                Label("Cancel download", systemImage: "xmark.circle")
            }
        } else if downloads.isDownloaded(itemId: itemId, episodeId: episode.id) {
            Button(role: .destructive) {
                downloads.delete(itemId: itemId, episodeId: episode.id)
            } label: {
                Label("Remove download", systemImage: "trash")
            }
        } else {
            Button {
                Task {
                    if let podcast = await state.item(itemId) {
                        downloads.start(podcast: podcast, episode: episode)
                    }
                }
            } label: {
                Label("Download", systemImage: "arrow.down.circle")
            }
        }
    }
}

extension View {
    func episodeMenu(
        itemId: String,
        episode: PodcastEpisode,
        podcastTitle: String,
        showGoToPodcast: Bool = true,
        extraRemove: (() -> Void)? = nil,
        playlistPickerFor: Binding<PlaylistEntry?>
    ) -> some View {
        modifier(EpisodeMenu(
            itemId: itemId, episode: episode, podcastTitle: podcastTitle,
            showGoToPodcast: showGoToPodcast, extraRemove: extraRemove,
            playlistPickerFor: playlistPickerFor
        ))
    }
}

// MARK: - Multi-select bar (Latest + Episodes bulk actions)

struct SelectionBar: View {
    let selectedCount: Int
    let totalCount: Int
    let onSelectAll: () -> Void
    let onSelectNone: () -> Void
    let onDownload: () -> Void
    let onAddToPlaylist: () -> Void
    let onDone: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            Text("\(selectedCount) selected").font(.footnote.weight(.medium))
            Spacer()
            Button(selectedCount == totalCount ? "None" : "All") {
                selectedCount == totalCount ? onSelectNone() : onSelectAll()
            }
            Button { onDownload() } label: { Image(systemName: "arrow.down.circle") }
                .disabled(selectedCount == 0)
            Button { onAddToPlaylist() } label: { Image(systemName: "text.badge.plus") }
                .disabled(selectedCount == 0)
            Button("Done") { onDone() }
        }
        .font(.footnote)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }
}

// MARK: - Playlist picker sheets

struct PlaylistPickerSheet: View {
    @ObservedObject var store = PlaylistStore.shared
    @Environment(\.dismiss) private var dismiss

    /** Entries to toggle/add; single-entry pickers toggle, bulk pickers add. */
    let entries: [PlaylistEntry]
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.playlists) { playlist in
                    Button {
                        toggle(playlist)
                    } label: {
                        HStack {
                            Text(playlist.name).foregroundStyle(.primary)
                            Spacer()
                            if entries.count == 1,
                               let entry = entries.first,
                               store.contains(playlist.id, itemId: entry.itemId, episodeId: entry.episodeId) {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
                Section {
                    HStack {
                        TextField("New playlist", text: $newName)
                        Button("Create") {
                            let name = newName.trimmingCharacters(in: .whitespaces)
                            guard !name.isEmpty else { return }
                            let playlist = store.create(name: name)
                            entries.forEach { store.add(playlist.id, entry: $0) }
                            dismiss()
                        }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }
            .navigationTitle(entries.count == 1 ? "Add to playlist" : "Add \(entries.count) episodes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func toggle(_ playlist: Playlist) {
        if entries.count == 1, let entry = entries.first {
            if store.contains(playlist.id, itemId: entry.itemId, episodeId: entry.episodeId) {
                store.remove(playlist.id, itemId: entry.itemId, episodeId: entry.episodeId)
            } else {
                store.add(playlist.id, entry: entry)
            }
        } else {
            entries.forEach { store.add(playlist.id, entry: $0) }
            dismiss()
        }
    }
}

// MARK: - Offline UI

struct OfflineBanner: View {
    var body: some View {
        Label("Offline — showing downloaded content", systemImage: "wifi.slash")
            .font(.footnote)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(.orange.opacity(0.25))
    }
}

struct OfflineTabHint: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.slash").font(.largeTitle).foregroundStyle(.secondary)
            Text("You're offline").font(.headline)
            Text("Go to Playlist → Downloaded to play saved episodes.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }
}
