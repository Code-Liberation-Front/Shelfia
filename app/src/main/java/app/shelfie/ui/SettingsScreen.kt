package app.shelfie.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.shelfie.ShelfieApp
import app.shelfie.data.Library
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

        SettingsCard(title = "Library") {
            val libraries by produceState<List<Library>?>(initialValue = null) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        if (app.repository.ensureConfigured()) app.repository.libraries() else null
                    }.getOrNull()
                }
            }
            val available = libraries
            when {
                available == null -> SettingsLine("Libraries", "Loading…")

                available.isEmpty() -> SettingsLine("Libraries", "None found")

                else -> {
                    val currentId = credentials?.libraryId
                        ?.ifBlank { available.first().id }
                        ?: available.first().id
                    val current = available.firstOrNull { it.id == currentId } ?: available.first()
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${current.name} (${libraryTypeLabel(current.mediaType)})",
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose library")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            available.forEach { library ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(library.name)
                                            Text(
                                                libraryTypeLabel(library.mediaType),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    trailingIcon = if (library.id == currentId) {
                                        {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            runCatching { app.repository.selectLibrary(library.id) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        SettingsCard(title = "Playback") {
            val autoPlay by app.settings.autoPlay.collectAsState(initial = true)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto play", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Continue to the next episode automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoPlay,
                    onCheckedChange = { enabled ->
                        scope.launch { app.settings.setAutoPlay(enabled) }
                    },
                )
            }
            val normalize by app.settings.normalizeAudio.collectAsState(initial = false)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Normalize volume", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Limit volume peaks like loud ad breaks (Android 9+)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = normalize,
                    onCheckedChange = { enabled ->
                        scope.launch { app.settings.setNormalizeAudio(enabled) }
                    },
                )
            }
        }

        SettingsCard(title = "Downloads") {
            val location by app.settings.downloadLocation.collectAsState(initial = "internal")
            val treeUri by app.settings.downloadTreeUri.collectAsState(initial = "")
            val context = LocalContext.current
            val externalAvailable = remember { app.getExternalFilesDir("episodes") != null }
            var locationMenuOpen by remember { mutableStateOf(false) }
            // System folder picker for a user-chosen download path.
            val folderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    scope.launch { app.settings.setCustomDownloadTree(uri.toString()) }
                }
            }
            // On Android 12 and below ask for the legacy storage permissions before
            // switching to shared storage; newer versions need none for app folders.
            val storagePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                scope.launch { app.settings.setDownloadLocation("external") }
            }

            fun selectLocation(value: String) {
                if (value == "external" && Build.VERSION.SDK_INT <= 32 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    val permissions = buildList {
                        add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }.toTypedArray()
                    storagePermissionLauncher.launch(permissions)
                } else {
                    scope.launch { app.settings.setDownloadLocation(value) }
                }
            }

            Box {
                OutlinedButton(
                    onClick = { locationMenuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (location) {
                            "external" -> "Shared app storage"
                            "custom" -> "Custom folder"
                            else -> "Internal app storage"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose download location")
                }
                DropdownMenu(
                    expanded = locationMenuOpen,
                    onDismissRequest = { locationMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Internal app storage (default)")
                                Text(
                                    app.filesDir.resolve("episodes").path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingIcon = if (location != "external") {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                        onClick = {
                            locationMenuOpen = false
                            selectLocation("internal")
                        },
                    )
                    if (externalAvailable) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Shared app storage")
                                    Text(
                                        app.getExternalFilesDir("episodes")?.path ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingIcon = if (location == "external") {
                                { Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                            } else {
                                null
                            },
                            onClick = {
                                locationMenuOpen = false
                                selectLocation("external")
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Custom folder…")
                                Text(
                                    if (location == "custom" && treeUri.isNotBlank()) {
                                        Uri.parse(treeUri).lastPathSegment ?: "Chosen folder"
                                    } else {
                                        "Pick any folder on this device"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingIcon = if (location == "custom") {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                        onClick = {
                            locationMenuOpen = false
                            folderPicker.launch(null)
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "New downloads are saved to the selected location. Existing downloads stay playable where they are.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

private fun libraryTypeLabel(mediaType: String): String = when (mediaType) {
    "podcast" -> "Podcasts"
    "book" -> "Audiobooks"
    else -> mediaType.replaceFirstChar { it.uppercase() }
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
