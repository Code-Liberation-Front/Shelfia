package app.shelfie.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "shelfie_settings")

data class Credentials(
    val serverUrl: String = "",
    val token: String = "",
    val userId: String = "",
    val libraryId: String = "",
) {
    val isLoggedIn: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val libraryId = stringPreferencesKey("library_id")
        val pendingOidcServer = stringPreferencesKey("pending_oidc_server")
        val pendingOidcVerifier = stringPreferencesKey("pending_oidc_verifier")
    }

    val credentials: Flow<Credentials> = context.dataStore.data.map { prefs ->
        Credentials(
            serverUrl = prefs[Keys.serverUrl] ?: "",
            token = prefs[Keys.token] ?: "",
            userId = prefs[Keys.userId] ?: "",
            libraryId = prefs[Keys.libraryId] ?: "",
        )
    }

    suspend fun snapshot(): Credentials = credentials.first()

    suspend fun saveLogin(serverUrl: String, token: String, userId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.serverUrl] = serverUrl
            prefs[Keys.token] = token
            prefs[Keys.userId] = userId
        }
    }

    suspend fun saveLibraryId(libraryId: String) {
        context.dataStore.edit { prefs -> prefs[Keys.libraryId] = libraryId }
    }

    /** Stores the server + PKCE verifier while the OIDC flow round-trips through the browser. */
    suspend fun savePendingOidc(serverUrl: String, codeVerifier: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.pendingOidcServer] = serverUrl
            prefs[Keys.pendingOidcVerifier] = codeVerifier
        }
    }

    suspend fun pendingOidc(): Pair<String, String>? {
        val prefs = context.dataStore.data.first()
        val server = prefs[Keys.pendingOidcServer] ?: return null
        val verifier = prefs[Keys.pendingOidcVerifier] ?: return null
        return server to verifier
    }

    suspend fun clearPendingOidc() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.pendingOidcServer)
            prefs.remove(Keys.pendingOidcVerifier)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
