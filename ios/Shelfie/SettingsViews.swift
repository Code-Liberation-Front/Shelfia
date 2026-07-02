import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var state: AppState
    @ObservedObject private var downloads = DownloadCenter.shared
    @State private var autoPlay = Settings.autoPlay
    @State private var stats: ListeningStats?

    var body: some View {
        List {
            Section("Account") {
                if !Settings.username.isEmpty {
                    LabeledContent("User", value: Settings.username)
                }
                LabeledContent("Server", value: state.client.serverUrl)
            }

            Section("Library") {
                Picker("Active library", selection: Binding(
                    get: { state.activeLibraryId },
                    set: { id in Task { await state.selectLibrary(id) } }
                )) {
                    ForEach(state.libraries) { library in
                        Text("\(library.name ?? "Library") · \(library.mediaType == "podcast" ? "Podcasts" : "Audiobooks")")
                            .tag(library.id)
                    }
                }
            }

            Section("Playback") {
                Toggle("Auto play next episode", isOn: $autoPlay)
                    .onChange(of: autoPlay) { Settings.autoPlay = $0 }
            }

            Section("Downloads") {
                NavigationLink {
                    DownloadsView()
                } label: {
                    HStack {
                        Text("Downloads")
                        Spacer()
                        Text(downloadsBadge).foregroundStyle(.secondary)
                    }
                }
            }

            Section("Listening stats") {
                if let stats {
                    LabeledContent("Total", value: formatListeningTime(stats.totalTime ?? 0))
                    LabeledContent("Today", value: formatListeningTime(stats.today ?? 0))
                } else {
                    ProgressView()
                }
            }

            Section {
                if let url = URL(string: state.client.serverUrl) {
                    Link("Open Audiobookshelf in browser", destination: url)
                }
                Button("Switch server / account", role: .destructive) {
                    state.logout()
                }
            }
        }
        .navigationTitle("Settings")
        .task {
            stats = try? await state.client.listeningStats()
        }
    }

    private var downloadsBadge: String {
        if !downloads.active.isEmpty {
            return "\(downloads.active.count) in progress"
        }
        let count = downloads.downloaded.count
        return count == 0 ? "None" : "\(count) episodes (\(formatBytes(downloads.totalSizeBytes)))"
    }
}

struct DownloadsView: View {
    @ObservedObject private var downloads = DownloadCenter.shared

    var body: some View {
        List {
            let active = downloads.active.values.sorted { $0.title < $1.title }
            if !active.isEmpty {
                Section("Downloading") {
                    ForEach(active) { download in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(download.title)
                                    .font(.subheadline.weight(.medium))
                                    .lineLimit(2)
                                Spacer()
                                Button {
                                    downloads.cancel(
                                        itemId: download.itemId, episodeId: download.episodeId
                                    )
                                } label: {
                                    Image(systemName: "xmark.circle").foregroundStyle(.secondary)
                                }
                            }
                            if download.failed {
                                Text("Download failed").font(.caption).foregroundStyle(.red)
                            } else if let fraction = download.fraction {
                                ProgressView(value: fraction)
                                Text("\(formatBytes(download.bytes)) / \(formatBytes(download.total)) (\(Int(fraction * 100))%) • \(formatBytes(Int64(download.speedBytesPerSec)))/s")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            } else {
                                ProgressView()
                            }
                        }
                    }
                }
            }

            let done = downloads.downloaded.sorted { $0.downloadedAt > $1.downloadedAt }
            Section("Downloaded • \(done.count) episodes • \(formatBytes(downloads.totalSizeBytes))") {
                if done.isEmpty {
                    Text("Nothing downloaded yet. Long-press an episode and choose “Download”.")
                        .foregroundStyle(.secondary)
                }
                ForEach(done) { entry in
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(entry.title).font(.subheadline.weight(.medium)).lineLimit(2)
                            Text("\(entry.podcastTitle) • \(formatBytes(entry.sizeBytes)) • \(Date(timeIntervalSince1970: entry.downloadedAt / 1000), style: .date)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button {
                            downloads.delete(itemId: entry.itemId, episodeId: entry.episodeId)
                        } label: {
                            Image(systemName: "trash").foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("Downloads")
    }
}
