import AVKit
import SwiftUI

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
                    if player.isBuffering {
                        ProgressView()
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    }
                }
                .frame(width: 24)
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
            VStack(spacing: 18) {
                CoverImage(url: now.coverUrl)
                    .frame(width: 260, height: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                VStack(spacing: 4) {
                    Text(now.title)
                        .font(.title3.bold())
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                    Text(now.podcastTitle).foregroundStyle(.secondary)
                    if let ms = now.publishedAt, ms > 0 {
                        Text(Date(timeIntervalSince1970: ms / 1000), style: .date)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                VStack {
                    Slider(
                        value: Binding(
                            get: { min(player.positionSec, max(now.durationSec, 1)) },
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
                        if player.isBuffering {
                            ProgressView()
                                .controlSize(.large)
                                .frame(width: 72, height: 72)
                        } else {
                            Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                                .font(.system(size: 72))
                        }
                    }
                    Button { player.skip(30) } label: {
                        Image(systemName: "goforward.30").font(.system(size: 34))
                    }
                }

                HStack(spacing: 24) {
                    Menu {
                        ForEach(speeds, id: \.self) { speed in
                            Button(speedLabel(speed)) { player.setRate(speed) }
                        }
                    } label: {
                        Label(speedLabel(player.rate), systemImage: "speedometer")
                    }
                    AirPlayButton()
                        .frame(width: 36, height: 36)
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

/** AirPlay output picker (iOS counterpart of the Android Cast button). */
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.prioritizesVideoDevices = false
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
