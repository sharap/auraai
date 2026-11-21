package music.ai.recommend.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import music.ai.recommend.platform.PhraseKept
import music.ai.recommend.MusicViewModel
import music.ai.recommend.platform.pickDirectory
import music.ai.recommend.platform.pickImageFile
import music.ai.recommend.model.*
import music.ai.recommend.ai.SmartAlbumClustering

@Composable
fun SettingsScreen(viewModel: MusicViewModel) {
    val isScanning by viewModel.isScanning.collectAsState()
    val isAiScanning by viewModel.isAiScanning.collectAsState()
    val aiScanProgress by viewModel.aiScanProgress.collectAsState()
    val aiScanStatus by viewModel.aiScanStatus.collectAsState()
    val scannedSongIds by viewModel.scannedSongIds.collectAsState()
    val analysisReset by viewModel.analysisReset.collectAsState()
    val housekeeping by viewModel.vectorHousekeeping.collectAsState()
    val modelProgress by viewModel.modelProgress.collectAsState()
    val modelsReady by viewModel.modelsReady.collectAsState()
    val modelsPendingBytes by viewModel.modelsPendingBytes.collectAsState()
    val smartAlbumsEpsScale by viewModel.smartAlbumsEpsScale.collectAsState()
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsState()
    val backgroundImageUri by viewModel.backgroundImageUri.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val musicFolderPath by viewModel.musicFolderPath.collectAsState()
    val syncSummary by viewModel.syncSummary.collectAsState()
    val eqBands by viewModel.eqBands.collectAsState()
    val eqPresets by viewModel.eqPresets.collectAsState()

    var newPresetName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text("Library", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Music Folder", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (musicFolderPath.isEmpty()) "Default (~/Music)" else musicFolderPath,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = {
                            val path = pickDirectory()
                            if (path != null) {
                                viewModel.updateMusicPath(path)
                            }
                        }) {
                            Text("Change")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(modifier = Modifier.graphicsLayer { alpha = 0.1f })
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Refresh Library", style = MaterialTheme.typography.titleMedium)
                            Text("Re-scan selected folder for changes", style = MaterialTheme.typography.bodySmall)
                        }
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            IconButton(onClick = { viewModel.loadMusic() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Dark Theme", style = MaterialTheme.typography.titleMedium)
                            Text(if (isDarkTheme) "Dark mode active" else "Light mode active", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = isDarkTheme,
                            onCheckedChange = { viewModel.setDarkTheme(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(modifier = Modifier.graphicsLayer { alpha = 0.1f })
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Image", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (backgroundImageUri != null) "Custom background active" else "Default theme background",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (backgroundImageUri != null) {
                            IconButton(onClick = { viewModel.setBackgroundImage(null) }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        }
                        Button(onClick = {
                            val path = pickImageFile()
                            if (path != null) {
                                viewModel.setBackgroundImage(path)
                            }
                        }) {
                            Text("Select")
                        }
                    }
                    if (backgroundImageUri != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Transparency", style = MaterialTheme.typography.titleSmall)
                        Slider(
                            value = backgroundAlpha,
                            onValueChange = { viewModel.setBackgroundAlpha(it) },
                            valueRange = 0.05f..1f
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("AI Models", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (modelsReady) "CLAP weights: ready" else "CLAP weights: not downloaded",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Said plainly, because it is a large download on someone else's connection.
                    Text(
                        "The weights are not shipped with the app. They come from Hugging Face " +
                            "once and are kept in ~/.aimusic/models.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (modelProgress.running) {
                        Text(
                            "Downloading ${modelProgress.currentFile ?: ""} " +
                                "(${modelProgress.bytesDone / (1024 * 1024)} of " +
                                "${modelProgress.bytesTotal / (1024 * 1024)} MB)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { modelProgress.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        modelProgress.error?.let { error ->
                            Text(
                                "Download failed: $error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!modelsReady) {
                                Button(onClick = { viewModel.downloadModels() }) {
                                    Text("Download (${modelsPendingBytes / (1024 * 1024)} MB)")
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (modelsPendingBytes == 0L || modelsReady) {
                                TextButton(onClick = { viewModel.deleteModels() }) {
                                    Text("Remove weights", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("AI Analysis", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        // The library's own tracks, not the rows held: those outlive the library,
                        // and a count of them reads as a library analysed past its own size.
                        "Analyzed songs: ${if (housekeeping.analysed > 0) housekeeping.analysed else scannedSongIds.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Without this the library would look as though it had un-analysed itself.
                    if (analysisReset) {
                        Text(
                            "Audio processing was corrected, so the previous analysis no longer " +
                                "applies. Run the scan again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (isAiScanning) {
                        Text(aiScanStatus, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { aiScanProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.stopAiScan() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Stop Analysis")
                        }
                    } else {
                        Text(
                            if (aiScanStatus.isNotEmpty()) aiScanStatus else "Run AI scan to enable smart features",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.startAiScan() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Start AI Scan")
                        }
                    }
                    
                    // Files that would not read, and vectors whose track is gone. Shown only when
                    // there are any: a library with neither should not be told about either.
                    if (housekeeping.unreadable > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Could not be read: ${housekeeping.unreadable}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Usually a broken file — a download that stopped, or a playlist saved " +
                                "as .mp3. Replace the file and the next scan will pick it up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (housekeeping.orphaned > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Vectors with no track: ${housekeeping.orphaned}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (housekeeping.orphanedInHistory > 0) {
                            Text(
                                "${housekeeping.orphanedInHistory} of them are still in your " +
                                    "listening history, and those are kept.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (housekeeping.removable > 0) {
                            TextButton(onClick = { viewModel.clearOrphanedVectors() }) {
                                Text("Remove ${housekeeping.removable}")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.clearAiData() }) {
                        Text("Clear AI Data", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Smart Albums", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // The radius is chosen per group, in that group's own reduced space, so an
                    // absolute value would mean nothing across libraries; this scales it.
                    var dragged by remember(smartAlbumsEpsScale) { mutableStateOf(smartAlbumsEpsScale) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Grouping radius (eps)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (dragged == 1f) "Auto" else "\u00D7${(dragged * 100).toInt() / 100f}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "Multiplier on the eps chosen automatically. Lower makes albums tighter " +
                            "and smaller, leaving more tracks out; higher groups more loosely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = dragged,
                        onValueChange = { dragged = it },
                        // Regrouping is seconds of work, so it waits for the drag to end.
                        onValueChangeFinished = { viewModel.setSmartAlbumsEpsScale(dragged) },
                        valueRange = SmartAlbumClustering.MIN_EPS_SCALE..SmartAlbumClustering.MAX_EPS_SCALE,
                        steps = ((SmartAlbumClustering.MAX_EPS_SCALE - SmartAlbumClustering.MIN_EPS_SCALE) * 20).toInt() - 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tighter", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.weight(1f))
                        if (smartAlbumsEpsScale != 1f) {
                            TextButton(onClick = { viewModel.setSmartAlbumsEpsScale(1f) }) { Text("Auto") }
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        Text("Looser", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Sync", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // What carries sync, which is the owner's choice: this app over the network,
                    // or a folder something else keeps in step.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Straight over the network", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "This app carries sync itself, with no shared folder and no other " +
                                    "program. Both devices need the same phrase; this machine " +
                                    "listens for as long as it is running.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = syncSummary.direct,
                            onCheckedChange = { viewModel.enableDirectSync(it) }
                        )
                    }

                    if (syncSummary.direct) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (syncSummary.listening) "Listening on port ${syncSummary.port}"
                            else "Not listening",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (syncSummary.addresses.isNotEmpty()) {
                            Text(
                                "This machine: ${syncSummary.addresses.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            if (syncSummary.peers.isEmpty()) "No other device heard yet"
                            else "Found: ${syncSummary.peers.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        syncSummary.peerTrouble?.let { trouble ->
                            Text(
                                "Last attempt: $trouble",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        // Listening and never once reached is what a closed firewall looks like
                        // from the inside, and it looks exactly like everything working.
                        if (syncSummary.listening && syncSummary.served == 0) {
                            Text(
                                "Nothing has reached this machine yet. A firewall is the usual " +
                                    "reason — Fedora's default zone allows ssh and mDNS and " +
                                    "nothing else, so neither the beacon nor a connection gets " +
                                    "in. Either open the two ports:\n" +
                                    "sudo firewall-cmd --add-port=${syncSummary.port}/tcp " +
                                    "--add-port=54334/udp --permanent && sudo firewall-cmd --reload\n" +
                                    "or type the phone's address above — this machine dials out " +
                                    "either way, and outgoing is not blocked.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        var address by remember { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = address,
                                onValueChange = { address = it },
                                label = { Text("Address of the other device") },
                                placeholder = { Text("only where broadcasts do not arrive") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    viewModel.setPeerAddress(address)
                                    address = ""
                                },
                                enabled = address.isNotBlank()
                            ) {
                                Text("Save")
                            }
                        }
                        if (syncSummary.peerAddressInUse.isNotEmpty()) {
                            Text(
                                "Dialling ${syncSummary.peerAddressInUse} as well as whoever answers.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(modifier = Modifier.graphicsLayer { alpha = 0.1f })
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (!syncSummary.direct) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Shared folder", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    syncSummary.folder ?: "Not set up",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    // What this is for, since a folder on its own explains nothing:
                                    // the two apps leave files for each other in it and never talk.
                                    "Listening history is exchanged through a folder something else " +
                                        "keeps in step — Syncthing, a network share, a stick.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = {
                                val path = pickDirectory()
                                if (path != null) viewModel.setSyncFolder(path)
                            }) {
                                Text(if (syncSummary.folder == null) "Choose" else "Change")
                            }
                        }
                    }

                    // Everything below is about what travels rather than about how, so it is
                    // shown for either transport.
                    if (syncSummary.folder != null || syncSummary.direct) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(modifier = Modifier.graphicsLayer { alpha = 0.1f })
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("This machine: ${syncSummary.deviceId}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (syncSummary.lastRunAt == 0L) "Not run yet"
                                    else "Sent ${syncSummary.sent}, taken in ${syncSummary.takenIn}" +
                                        if (syncSummary.waiting > 0) ", ${syncSummary.waiting} waiting for their tracks" else "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (syncSummary.busy) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                IconButton(onClick = { viewModel.syncNow() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                }
                            }
                        }

                        // Vectors get a line of their own, and only once something has moved: for
                        // most people this is a one-off backfill they should be able to watch
                        // finish, and after that a line that always reads zero would be noise.
                        // Playlist changes get their own line, and only once something has moved.
                        if (syncSummary.changesSent > 0 || syncSummary.changesTakenIn > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Playlists: sent ${syncSummary.changesSent}, " +
                                    "taken in ${syncSummary.changesTakenIn}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (syncSummary.vectorsSent > 0 || syncSummary.vectorsTakenIn > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Vectors: sent ${syncSummary.vectorsSent}, " +
                                    "taken in ${syncSummary.vectorsTakenIn}" +
                                    if (syncSummary.vectorsWaiting) " · more to come" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // The phrase, and what the folder looks like without one. The mix line
                        // and this one are the only places that say anything about the folder's
                        // own safety, and the second is the one that matters: Syncthing protects
                        // the wire, nothing protects the folder.
                        if (syncSummary.mixSent > 0 || syncSummary.mixTakenIn > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Playlist of the day: sent ${syncSummary.mixSent}, " +
                                    "taken in ${syncSummary.mixTakenIn}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (syncSummary.sealed)
                                "The folder is sealed: what this machine writes can only be read " +
                                    "with the phrase."
                            else
                                "The folder is plain text. Anything that can read it can read a " +
                                    "year of listening.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        // Where the one secret of this arrangement is kept. Said plainly, because
                        // the promise is otherwise unverifiable from the outside, and because the
                        // last of the three is one the owner may want to do something about.
                        if (syncSummary.sealed) {
                            Text(
                                when (syncSummary.phraseKept) {
                                    PhraseKept.KEYRING ->
                                        "The phrase is in this desktop's keyring, where the rest " +
                                            "of the desktop keeps its passwords."
                                    PhraseKept.FILE ->
                                        "There is no keyring here, so the phrase is in a file " +
                                            "only you can read."
                                    PhraseKept.PLAIN ->
                                        "The phrase is in the settings file, which anyone on this " +
                                            "machine can read: neither the keyring nor a file of " +
                                            "its own would take it."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (syncSummary.phraseKept == PhraseKept.PLAIN) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (syncSummary.unopened > 0) {
                            Text(
                                "${syncSummary.unopened} batches would not open. The phrase here " +
                                    "is probably not the phrase there.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        var phrase by remember { mutableStateOf("") }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = phrase,
                                onValueChange = { phrase = it },
                                label = { Text("Shared phrase") },
                                placeholder = { Text("The same words on both devices") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    viewModel.setSyncPhrase(phrase)
                                    phrase = ""
                                },
                                enabled = phrase.isNotBlank()
                            ) {
                                Text("Set")
                            }
                            if (syncSummary.sealed) {
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(onClick = {
                                    viewModel.setSyncPhrase("")
                                    phrase = ""
                                }) {
                                    Text("Clear")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        if (!syncSummary.direct) TextButton(onClick = { viewModel.forgetSyncFolder() }) {
                            Text("Forget this folder", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Equalizer", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Presets Row
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(eqPresets) { preset ->
                            SuggestionChip(
                                onClick = { viewModel.applyPreset(preset) },
                                label = { Text(preset.name) },
                                icon = {
                                    if (preset.isCustom) {
                                        IconButton(
                                            onClick = { viewModel.deletePreset(preset) },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Save Preset Field
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = newPresetName,
                            onValueChange = { newPresetName = it },
                            placeholder = { Text("Preset name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newPresetName.isNotBlank()) {
                                    viewModel.saveCustomPreset(newPresetName)
                                    newPresetName = ""
                                }
                            },
                            enabled = newPresetName.isNotBlank()
                        ) {
                            Text("Save")
                        }
                    }

                    // Sliders Row
                    Row(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        eqBands.forEach { band ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${band.level.toInt()}dB", style = MaterialTheme.typography.labelSmall)
                                VerticalSlider(
                                    value = band.level,
                                    onValueChange = { viewModel.setEqBandLevel(band.index, it) },
                                    valueRange = -20f..20f,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(band.freq, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { 
                        viewModel.applyPreset(EqPreset("Flat", List(10) { 0f }))
                    }) {
                        Text("Reset")
                    }
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Box(
        modifier = modifier
            .width(32.dp)
            .graphicsLayer {
                rotationZ = 270f
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(-((placeable.width - placeable.height) / 2), -((placeable.height - placeable.width) / 2))
                }
            }
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
