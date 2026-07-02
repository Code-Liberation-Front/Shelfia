import Foundation

/** UserDefaults-backed settings, mirroring the Android SettingsStore keys. */
enum Settings {
    private static var defaults: UserDefaults { .standard }

    static var serverUrl: String {
        get { defaults.string(forKey: "serverUrl") ?? "" }
        set { defaults.set(newValue, forKey: "serverUrl") }
    }
    static var token: String {
        get { defaults.string(forKey: "token") ?? "" }
        set { defaults.set(newValue, forKey: "token") }
    }
    static var userId: String {
        get { defaults.string(forKey: "userId") ?? "" }
        set { defaults.set(newValue, forKey: "userId") }
    }
    static var username: String {
        get { defaults.string(forKey: "username") ?? "" }
        set { defaults.set(newValue, forKey: "username") }
    }

    /** Active library; empty means "first podcast library, else first". */
    static var libraryId: String {
        get { defaults.string(forKey: "libraryId") ?? "" }
        set { defaults.set(newValue, forKey: "libraryId") }
    }

    /** Queue the whole podcast and continue to the next episode. */
    static var autoPlay: Bool {
        get { defaults.object(forKey: "autoPlay") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "autoPlay") }
    }

    // Last played media id + position, for CarPlay playback resumption.
    static var lastPlayedMediaId: String {
        get { defaults.string(forKey: "lastPlayedMediaId") ?? "" }
        set { defaults.set(newValue, forKey: "lastPlayedMediaId") }
    }
    static var lastPlayedPositionSec: Double {
        get { defaults.double(forKey: "lastPlayedPositionSec") }
        set { defaults.set(newValue, forKey: "lastPlayedPositionSec") }
    }

    static func saveLogin(server: String, token: String, userId: String, username: String) {
        self.serverUrl = server
        self.token = token
        self.userId = userId
        self.username = username
    }

    static func clearLogin() {
        for key in ["serverUrl", "token", "userId", "username", "libraryId",
                    "lastPlayedMediaId", "lastPlayedPositionSec"] {
            defaults.removeObject(forKey: key)
        }
    }
}
