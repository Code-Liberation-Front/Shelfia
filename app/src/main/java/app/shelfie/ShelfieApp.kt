package app.shelfie

import android.app.Application
import app.shelfie.data.AbsRepository
import app.shelfie.data.SettingsStore
import app.shelfie.download.DownloadCenter
import app.shelfie.playlist.PlaylistStore
import java.io.File

class ShelfieApp : Application() {

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val repository: AbsRepository by lazy {
        AbsRepository(settings, cacheDir = File(filesDir, "apicache"))
    }
    val downloads: DownloadCenter by lazy { DownloadCenter(this, repository) }
    val playlist: PlaylistStore by lazy { PlaylistStore(this) }
}
