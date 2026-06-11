package app.shelfie.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.shelfie.ShelfieApp
import app.shelfie.data.ListeningStats
import app.shelfie.ui.theme.ShelfieSurfaceHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(app: ShelfieApp, onOpenDownloads: () -> Unit, onBack: () -> Unit) {
    val credentials by app.settings.credentials.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val stats by produceState<ListeningStats?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (app.repository.ensureConfigured()) app.repository.listeningStats() else null
            }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        SettingsCard(title = "Account") {
            SettingsLine("User", credentials?.username?.ifBlank { "—" } ?: "—")
            SettingsLine("Server", credentials?.serverUrl?.ifBlank { "—" } ?: "—")
        }

        SettingsCard(title = "Listening stats") {
            SettingsLine(
                "Total listening time",
                stats?.let { formatListeningTime(it.totalTime) } ?: "—",
            )
            SettingsLine(
                "Today",
                stats?.let { formatListeningTime(it.today) } ?: "—",
            )
        }

        val downloads by app.downloads.completed.collectAsState()
        val activeDownloads by app.downloads.active.collectAsState()
        OutlinedButton(
            onClick = onOpenDownloads,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val label = buildString {
                append("Downloads")
                if (activeDownloads.isNotEmpty()) {
                    append(" • ${activeDownloads.size} in progress")
                } else if (downloads.isNotEmpty()) {
                    append(" • ${downloads.size} episodes (${formatBytes(downloads.sumOf { it.sizeBytes })})")
                }
            }
            Text(label)
        }

        val context = LocalContext.current
        OutlinedButton(
            onClick = {
                val url = credentials?.serverUrl
                if (!url.isNullOrBlank()) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Open Audiobookshelf in browser")
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching { app.repository.logout() }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Switch server / account")
        }
        Text(
            "Signing out clears the saved server and credentials, then returns to the login screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ShelfieSurfaceHigh),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingsLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
