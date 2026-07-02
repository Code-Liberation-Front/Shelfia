import SwiftUI

struct MainView: View {
    @EnvironmentObject var state: AppState
    @StateObject private var player = PlayerManager.shared

    var body: some View {
        TabView {
            NavigationStack { LibraryView() }
                .tabItem { Label("Library", systemImage: "square.grid.2x2") }
            NavigationStack { ContinueView() }
                .tabItem { Label("Continue", systemImage: "play.circle") }
            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .safeAreaInset(edge: .bottom) {
            if player.current != nil { MiniPlayerBar() }
        }
        .task { await state.refresh() }
    }
}

struct LibraryView: View {
    @EnvironmentObject var state: AppState
    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 12)]

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
                        }
                    }
                }
            }
            .padding(12)
        }
        .navigationTitle("Library")
        .navigationDestination(for: String.self) { itemId in
            PodcastDetailView(itemId: itemId)
        }
        .refreshable { await state.refresh() }
    }
}

struct PodcastDetailView: View {
    @EnvironmentObject var state: AppState
    let itemId: String
    @State private var podcast: ItemExpanded?

    var body: some View {
        List {
            if let podcast, let episodes = podcast.media?.episodes {
                ForEach(episodes.sorted { ($0.publishedAt ?? 0) > ($1.publishedAt ?? 0) }) { episode in
                    EpisodeRow(podcast: podcast, episode: episode)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .listStyle(.plain)
        .navigationTitle(podcast?.title ?? "Podcast")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            podcast = try? await state.client.item(itemId)
            await state.refreshProgress()
        }
    }
}

struct EpisodeRow: View {
    @EnvironmentObject var state: AppState
    let podcast: ItemExpanded
    let episode: Episode

    var body: some View {
        let progress = state.progressFor(itemId: podcast.id, episodeId: episode.id)
        Button {
            PlayerManager.shared.play(podcast: podcast, episode: episode)
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(episode.title ?? "Episode")
                    .font(.subheadline.weight(.medium))
                    .lineLimit(3)
                    .foregroundStyle(progress?.isFinished == true ? .secondary : .primary)
                HStack(spacing: 6) {
                    if let published = episode.publishedAt {
                        Text(Date(timeIntervalSince1970: published / 1000), style: .date)
                    }
                    if episode.durationSec > 0 {
                        Text("•")
                        Text(formatDuration(episode.durationSec))
                    }
                    if progress?.isFinished == true {
                        Image(systemName: "checkmark.circle.fill")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                if let fraction = progress?.progress, fraction > 0.01, progress?.isFinished != true {
                    ProgressView(value: fraction).tint(.accentColor)
                }
            }
        }
    }
}

struct ContinueView: View {
    @EnvironmentObject var state: AppState
    @State private var rows: [(podcast: ItemExpanded, episode: Episode, progress: MediaProgress)] = []
    @State private var loading = true

    var body: some View {
        List {
            if loading {
                ProgressView().frame(maxWidth: .infinity)
            } else if rows.isEmpty {
                Text("Nothing in progress yet.").foregroundStyle(.secondary)
            } else {
                ForEach(rows, id: \.episode.id) { row in
                    EpisodeRow(podcast: row.podcast, episode: row.episode)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Continue Listening")
        .task {
            rows = await state.continueListening()
            loading = false
        }
        .refreshable {
            rows = await state.continueListening()
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        List {
            Section("Account") {
                Text(state.client.serverUrl).foregroundStyle(.secondary)
                Button("Sign out", role: .destructive) { state.logout() }
            }
        }
        .navigationTitle("Settings")
    }
}

// MARK: - Player UI

struct MiniPlayerBar: View {
    @StateObject private var player = PlayerManager.shared
    @State private var expanded = false

    var body: some View {
        if let now = player.current {
            HStack(spacing: 12) {
                CoverImage(url: now.coverUrl)
                    .frame(width: 40, height: 40)
                VStack(alignment: .leading) {
                    Text(now.title).font(.footnote.weight(.medium)).lineLimit(1)
                    Text(now.podcastTitle).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Button { player.skip(-10) } label: { Image(systemName: "gobackward.10") }
                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                }
                Button { player.skip(30) } label: { Image(systemName: "goforward.30") }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial)
            .contentShape(Rectangle())
            .onTapGesture { expanded = true }
            .sheet(isPresented: $expanded) { PlayerSheet() }
        }
    }
}

struct PlayerSheet: View {
    @StateObject private var player = PlayerManager.shared
    private let speeds: [Float] = [0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0]

    var body: some View {
        if let now = player.current {
            VStack(spacing: 20) {
                CoverImage(url: now.coverUrl)
                    .frame(width: 260, height: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                Text(now.title).font(.title3.bold()).multilineTextAlignment(.center)
                Text(now.podcastTitle).foregroundStyle(.secondary)

                VStack {
                    Slider(
                        value: Binding(
                            get: { player.positionSec },
                            set: { player.seek(to: $0) }
                        ),
                        in: 0...max(now.durationSec, 1)
                    )
                    HStack {
                        Text(formatDuration(player.positionSec))
                        Spacer()
                        Text("-" + formatDuration(max(now.durationSec - player.positionSec, 0)))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                HStack(spacing: 40) {
                    Button { player.skip(-10) } label: {
                        Image(systemName: "gobackward.10").font(.system(size: 34))
                    }
                    Button { player.togglePlayPause() } label: {
                        Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 72))
                    }
                    Button { player.skip(30) } label: {
                        Image(systemName: "goforward.30").font(.system(size: 34))
                    }
                }

                Menu {
                    ForEach(speeds, id: \.self) { speed in
                        Button(speedLabel(speed)) { player.setRate(speed) }
                    }
                } label: {
                    Label(speedLabel(player.rate), systemImage: "speedometer")
                }
            }
            .padding(24)
            .presentationDragIndicator(.visible)
        }
    }

    private func speedLabel(_ speed: Float) -> String {
        speed == speed.rounded() ? "\(Int(speed))x" : String(format: "%.2gx", speed)
    }
}

struct CoverImage: View {
    let url: URL?

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
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

func formatDuration(_ seconds: Double) -> String {
    let total = Int(seconds)
    let h = total / 3600, m = (total % 3600) / 60, s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}
