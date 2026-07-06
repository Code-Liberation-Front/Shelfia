import SwiftUI
import UIKit

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

// MARK: - Cover art cache (memory + disk) so lists render instantly offline

final class CoverCache {
    static let shared = CoverCache()

    private let memory = NSCache<NSString, UIImage>()
    private let dir: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("covers", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    /** Cached image only (memory, then disk); never touches the network. */
    func cached(_ url: URL) -> UIImage? {
        let key = cacheKey(url)
        if let image = memory.object(forKey: key as NSString) { return image }
        guard let data = try? Data(contentsOf: dir.appendingPathComponent(key)),
              let image = UIImage(data: data) else { return nil }
        memory.setObject(image, forKey: key as NSString)
        return image
    }

    func fetch(_ url: URL) async -> UIImage? {
        if let image = cached(url) { return image }
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let image = UIImage(data: data) else { return nil }
        try? data.write(to: dir.appendingPathComponent(cacheKey(url)))
        memory.setObject(image, forKey: cacheKey(url) as NSString)
        return image
    }

    /** Keyed on the URL path only, so the per-login token query is ignored. */
    private func cacheKey(_ url: URL) -> String {
        url.path.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
    }
}

// MARK: - Cover image with completion treatment (blur + check when finished)

struct CoverImage: View {
    let url: URL?
    var finished = false
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    Color(white: 0.16)
                    Image(systemName: "book.closed").foregroundStyle(.secondary)
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .task(id: url) {
            guard let url else {
                image = nil
                return
            }
            if let cached = CoverCache.shared.cached(url) {
                image = cached
            } else {
                image = await CoverCache.shared.fetch(url)
            }
        }
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

// MARK: - Top loading bar (replaces full-screen spinners during refresh)

/** Thin indeterminate bar pinned to the top of a screen while data refreshes. */
struct TopLoadingBar: View {
    @State private var animate = false

    var body: some View {
        GeometryReader { geo in
            Capsule()
                .fill(Color.accentColor)
                .frame(width: geo.size.width * 0.35, height: 3)
                .offset(x: animate ? geo.size.width : -geo.size.width * 0.35)
        }
        .frame(height: 3)
        .clipped()
        .onAppear {
            withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) {
                animate = true
            }
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
