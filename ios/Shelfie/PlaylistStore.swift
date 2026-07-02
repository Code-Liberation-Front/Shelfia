import Combine
import Foundation

struct PlaylistEntry: Codable, Identifiable, Equatable {
    let itemId: String
    let episodeId: String
    let title: String
    let podcastTitle: String

    var id: String { "\(itemId):\(episodeId)" }
}

struct Playlist: Codable, Identifiable, Equatable {
    let id: String
    var name: String
    var entries: [PlaylistEntry]
}

/** Local-only playlists persisted to Documents/playlists.json (Android parity). */
@MainActor
final class PlaylistStore: ObservableObject {
    static let shared = PlaylistStore()

    /** Id of the built-in "Downloaded" virtual playlist. */
    static let downloadedId = "__downloaded__"

    @Published private(set) var playlists: [Playlist] = []

    private var fileUrl: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("playlists.json")
    }

    private init() {
        if let data = try? Data(contentsOf: fileUrl),
           let list = try? JSONDecoder().decode([Playlist].self, from: data) {
            playlists = list
        }
    }

    func playlist(_ id: String) -> Playlist? {
        playlists.first { $0.id == id }
    }

    @discardableResult
    func create(name: String) -> Playlist {
        let playlist = Playlist(id: UUID().uuidString, name: name, entries: [])
        playlists.append(playlist)
        save()
        return playlist
    }

    func delete(_ id: String) {
        playlists.removeAll { $0.id == id }
        save()
    }

    func contains(_ id: String, itemId: String, episodeId: String) -> Bool {
        playlist(id)?.entries.contains { $0.itemId == itemId && $0.episodeId == episodeId } ?? false
    }

    func add(_ id: String, entry: PlaylistEntry) {
        guard let index = playlists.firstIndex(where: { $0.id == id }),
              !playlists[index].entries.contains(where: { $0.id == entry.id })
        else { return }
        playlists[index].entries.append(entry)
        save()
    }

    func remove(_ id: String, itemId: String, episodeId: String) {
        guard let index = playlists.firstIndex(where: { $0.id == id }) else { return }
        playlists[index].entries.removeAll { $0.itemId == itemId && $0.episodeId == episodeId }
        save()
    }

    func setEntries(_ id: String, entries: [PlaylistEntry]) {
        guard let index = playlists.firstIndex(where: { $0.id == id }) else { return }
        playlists[index].entries = entries
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(playlists) {
            try? data.write(to: fileUrl)
        }
    }
}
