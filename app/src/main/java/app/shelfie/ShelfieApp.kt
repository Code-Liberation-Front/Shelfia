package app.shelfie

import android.app.Application
import app.shelfie.data.AbsRepository
import app.shelfie.data.SettingsStore

class ShelfieApp : Application() {

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val repository: AbsRepository by lazy { AbsRepository(settings) }
}
