import SwiftUI

/**
 Playlist tab: a built-in "Downloaded" virtual playlist plus local user
 playlists with reorder, play-all, and download-all (Android parity).
 */
struct PlaylistScreen: View {
    @EnvironmentObject var state: AppState
    @ObservedObject private var store = PlaylistStore.shared
    @ObservedObject private var downloads = DownloadCenter.shared

    @State private var selectedId: String = PlaylistStore.downloadedId
    @State private var showCreate = false
    @State private var newName = ""
    @State private var playlistPickerFor: PlaylistEntry?

    /** Falls back to Downloaded when the selected playlist disappears. */
    private var effectiveId: String {
        if selectedId != PlaylistStore.downloadedId && store.playlist(selectedId) == nil {
            return PlaylistStore.downloadedId
        }
        return selectedId
    }

    var body: some View {
        VStack(spacing: 0) {
            chips
            content
        }
        .navigationTitle("Playlist")
        .alert("New playlist", isPresented: $showCreate) {
            TextField("Name", text: $newName)
            Button("Create") {
                let name = newName.trimmingCharacters(in: .whitespaces)
                if !name.isEmpty {
                    let playlist = store.create(name: name)
                    selectedId = playlist.id
                }
                newName = ""
            }
            Button("Cancel", role: .cancel) { newName = "" }
        }
        .sheet(item: $playlistPickerFor) { entry in
            PlaylistPickerSheet(entries: [entry])
        }
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(title: "Downloaded", id: PlaylistStore.downloadedId)
                ForEach(store.playlists) { playlist in
                    chip(title: playlist.name, id: playlist.id)
                }
                Button {
                    showCreate = true
                } label: {
                    Label("New playlist", systemImage: "plus")
                        .font(.footnote.weight(.medium))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(Capsule().fill(Color(white: 0.2)))
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
        }
    }

    private func chip(title: String, id: String) -> some View {
        Button {
            selectedId = id
        } label: {
            Text(title)
                .font(.footnote.weight(.medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(
                    Capsule().fill(effectiveId == id ? Color.accentColor.opacity(0.35) : Color(white: 0.2))
                )
        }
        .foregroundStyle(.primary)
    }

    @ViewBuilder
    private var content: some View {
        if effectiveId == PlaylistStore.downloadedId {
            downloadedList
        } else if let playlist = store.playlist(effectiveId) {
            userPlaylist(playlist)
        }
    }

    // MARK: Downloaded virtual playlist

    private var downloadedList: some View {
        let list = downloads.downloaded.sorted { $0.downloadedAt > $1.downloadedAt }
        return List {
            if list.isEmpty {
                Text("No downloaded episodes yet.").foregroundStyle(.secondary)
            } else {
                Button {
                    PlayerManager.shared.playDownloaded(list, startAt: 0)
                } label: {
                    Label("Play all (\(list.count))", systemImage: "play.fill")
                }
                ForEach(Array(list.enumerated()), id: \.element.id) { index, entry in
                    Button {
                        PlayerManager.shared.playDownloaded(list, startAt: index)
                    } label: {
                        downloadedRow(entry)
                    }
                    .contextMenu {
                        Button(role: .destructive) {
                            downloads.delete(itemId: entry.itemId, episodeId: entry.episodeId)
                        } label: {
                            Label("Remove download", systemImage: "trash")
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    private func downloadedRow(_ entry: DownloadedEpisode) -> some View {
        let progress = state.progressFor(itemId: entry.itemId, episodeId: entry.episodeId)
        return HStack(spacing: 12) {
            CoverImage(
                url: state.client.coverUrl(entry.itemId),
                finished: progress?.finished == true
            )
            .frame(width: 52, height: 52)
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.title).font(.subheadline.weight(.medium)).lineLimit(2)
                Text(entry.podcastTitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                Text("\(formatBytes(entry.sizeBytes)) • \(Date(timeIntervalSince1970: entry.downloadedAt / 1000), style: .date)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let fraction = progress?.progress, fraction > 0.01, progress?.finished != true {
                    ProgressView(value: min(fraction, 1)).tint(.accentColor)
                }
            }
            Spacer()
        }
    }

    // MARK: User playlists

    private func userPlaylist(_ playlist: Playlist) -> some View {
        List {
            HStack {
                Button {
                    Task { await PlayerManager.shared.playPlaylist(playlist.entries, startAt: 0) }
                } label: {
                    Label("Play all (\(playlist.entries.count))", systemImage: "play.fill")
                }
                .disabled(playlist.entries.isEmpty)
                Spacer()
                EditButton()
                Menu {
                    Button {
                        downloadAll(playlist)
                    } label: {
                        Label("Download all", systemImage: "arrow.down.circle")
                    }
                    Button(role: .destructive) {
                        store.delete(playlist.id)
                        selectedId = PlaylistStore.downloadedId
                    } label: {
                        Label("Delete playlist", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
            if playlist.entries.isEmpty {
                Text("No episodes yet. Long-press any episode and choose “Add to playlist”.")
                    .foregroundStyle(.secondary)
            }
            ForEach(Array(playlist.entries.enumerated()), id: \.element.id) { index, entry in
                Button {
                    Task { await PlayerManager.shared.playPlaylist(playlist.entries, startAt: index) }
                } label: {
                    playlistRow(entry)
                }
                .contextMenu {
                    Button(role: .destructive) {
                        store.remove(playlist.id, itemId: entry.itemId, episodeId: entry.episodeId)
                    } label: {
                        Label("Remove from playlist", systemImage: "minus.circle")
                    }
                    Button {
                        Router.shared.goToPodcast(entry.itemId)
                    } label: {
                        Label("Go to podcast", systemImage: "square.grid.2x2")
                    }
                }
            }
            .onMove { source, destination in
                var entries = playlist.entries
                entries.move(fromOffsets: source, toOffset: destination)
                store.setEntries(playlist.id, entries: entries)
            }
        }
        .listStyle(.plain)
    }

    private func playlistRow(_ entry: PlaylistEntry) -> some View {
        let progress = state.progressFor(itemId: entry.itemId, episodeId: entry.episodeId)
        let isDownloaded = downloads.isDownloaded(itemId: entry.itemId, episodeId: entry.episodeId)
        return HStack(spacing: 12) {
            CoverImage(
                url: state.client.coverUrl(entry.itemId),
                finished: progress?.finished == true
            )
            .frame(width: 52, height: 52)
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.title).font(.subheadline.weight(.medium)).lineLimit(2)
                HStack(spacing: 6) {
                    Text(entry.podcastTitle).lineLimit(1)
                    if isDownloaded {
                        Image(systemName: "arrow.down.circle.fill").foregroundStyle(.tint)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                if let fraction = progress?.progress, fraction > 0.01, progress?.finished != true {
                    ProgressView(value: min(fraction, 1)).tint(.accentColor)
                }
            }
            Spacer()
        }
    }

    private func downloadAll(_ playlist: Playlist) {
        Task {
            for entry in playlist.entries
            where !downloads.isDownloaded(itemId: entry.itemId, episodeId: entry.episodeId) {
                if let podcast = await state.item(entry.itemId),
                   let episode = podcast.episodes.first(where: { $0.id == entry.episodeId }) {
                    downloads.start(podcast: podcast, episode: episode)
                }
            }
        }
    }
}
