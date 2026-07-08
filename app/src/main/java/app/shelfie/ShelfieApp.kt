package app.shelfie

import android.app.Application
import app.shelfie.data.AbsRepository
import app.shelfie.data.InsecureTls
import app.shelfie.data.SettingsStore
import app.shelfie.download.DownloadCenter
import app.shelfie.playlist.PlaylistStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import java.io.File
import okhttp3.OkHttpClient

class ShelfieApp : Application(), ImageLoaderFactory {

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val repository: AbsRepository by lazy {
        AbsRepository(settings, cacheDir = File(filesDir, "apicache"))
    }
    val downloads: DownloadCenter by lazy { DownloadCenter(this, repository, settings) }
    val playlist: PlaylistStore by lazy { PlaylistStore(this) }

    override fun onCreate() {
        super.onCreate()
        // Self-hosted servers often use self-signed certificates; accept them
        // process-wide (Media3 streams audio over HttpsURLConnection).
        InsecureTls.installGlobal()
    }

    /** Coil loader for covers that accepts self-signed certificates too. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { InsecureTls.apply(OkHttpClient.Builder()).build() }
            .build()
}
