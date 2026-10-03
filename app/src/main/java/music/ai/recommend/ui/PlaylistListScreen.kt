package music.ai.recommend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import music.ai.recommend.ui.theme.LocalMutedOnBackground
import music.ai.recommend.MusicViewModel
import music.ai.recommend.WaveMode
import music.ai.recommend.Playlist
import music.ai.recommend.R
import music.ai.recommend.ai.SmartAlbum
import music.ai.recommend.model.Song

@Composable
fun PlaylistListScreen(
    viewModel: MusicViewModel,
    onPlaylistClick: (String) -> Unit,
    onSmartAlbumClick: (String) -> Unit,
    onDailyMixClick: () -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    val scanCounts by viewModel.playlistScanCounts.collectAsState()
    val smartAlbums by viewModel.smartAlbums.collectAsState()
    val dailyMix by viewModel.dailyMix.collectAsState()
    val waveMode by viewModel.waveMode.collectAsState()
    val waveDescription by viewModel.waveDescription.collectAsState()
    val waveStarting by viewModel.waveStarting.collectAsState()
    val waveMessage by viewModel.waveMessage.collectAsState()
    var describing by remember { mutableStateOf(false) }
    val dailyMixBuilding by viewModel.dailyMixBuilding.collectAsState()
    val building by viewModel.smartAlbumsBuilding.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val currentSongId = currentSong?.id
    // Smart albums do not overlap, so the playing track is in at most one of them.
    val playingAlbumId = remember(smartAlbums, currentSongId) {
        currentSongId?.let { id -> smartAlbums.firstOrNull { album -> album.songs.any { it.id == id } }?.id }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item(key = "wave") {
            WaveCard(
                mode = waveMode,
                description = waveDescription,
                starting = waveStarting,
                message = waveMessage,
                onStart = { viewModel.startWave(it) },
                onDescribe = { describing = true },
                onStop = { viewModel.stopWave() },
                onDismissMessage = { viewModel.clearWaveMessage() }
            )
        }

        if (dailyMix.isNotEmpty() || dailyMixBuilding) {
            item(key = "daily_mix") {
                DailyMixCard(
                    songs = dailyMix,
                    building = dailyMixBuilding,
                    containsCurrent = currentSongId != null && dailyMix.any { it.id == currentSongId },
                    onOpen = onDailyMixClick,
                    onPlay = { viewModel.playDailyMix() },
                    onRebuild = { viewModel.refreshDailyMix(rebuild = true) }
                )
            }
        }

        // Nothing to group until some tracks are analysed; the section stays out of the way.
        if (smartAlbums.isNotEmpty() || building || scannedIds.isNotEmpty()) {
            item(key = "smart_header") {
                SectionHeader(title = stringResource(id = R.string.smart_albums)) {
                    if (building) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { viewModel.refreshSmartAlbums(rebuild = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(id = R.string.smart_albums_refresh))
                        }
                    }
                }
            }
            if (smartAlbums.isEmpty()) {
                item(key = "smart_hint") {
                    Text(
                        text = stringResource(
                            id = if (building) R.string.smart_albums_building else R.string.smart_albums_hint
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMutedOnBackground.current,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            items(smartAlbums, key = { "smart_${it.id}" }) { album ->
                SmartAlbumItem(
                    album = album,
                    isActive = album.id == playingAlbumId,
                    onClick = { onSmartAlbumClick(album.id) }
                )
            }
            item(key = "playlists_header") {
                SectionHeader(title = stringResource(id = R.string.playlists_section))
            }
        }

        if (playlists.isEmpty()) {
            item(key = "no_playlists") {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(id = R.string.no_playlists_yet),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
        items(playlists, key = { it.name }) { playlist ->
            PlaylistItem(
                playlist = playlist,
                scannedCount = scanCounts[playlist.name] ?: 0,
                onClick = { onPlaylistClick(playlist.name) },
                onDelete = { viewModel.deletePlaylist(playlist) }
            )
        }
    }

    if (describing) {
        DescribeWaveDialog(
            onDismiss = { describing = false },
            onStart = { query ->
                describing = false
                viewModel.startWave(WaveMode.DESCRIPTION, query)
            }
        )
    }
}

/**
 * The playlist of the day. It gets a card rather than a row because it is the one thing on this
 * screen the user did not make themselves, and it changes every day.
 */
@Composable
private fun DailyMixCard(
    songs: List<Song>,
    building: Boolean,
    containsCurrent: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onRebuild: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Today,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.daily_mix),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (containsCurrent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = stringResource(id = R.string.now_playing_album),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (building && songs.isEmpty()) stringResource(id = R.string.daily_mix_building)
                    else stringResource(id = R.string.songs_count, songs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMutedOnBackground.current
                )
            }
            if (building) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRebuild) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(id = R.string.daily_mix_rebuild))
                }
            }
            IconButton(onClick = onPlay, enabled = songs.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(id = R.string.play),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * The wave: an endless stream that steers itself from skips and finished tracks.
 *
 * The three starting points are offered side by side rather than hidden behind a default, because
 * they answer different wishes — "the usual", "more of this", and "something specific right now" —
 * and only the listener knows which one it is.
 */
@Composable
private fun WaveCard(
    mode: WaveMode?,
    description: String?,
    starting: Boolean,
    message: String?,
    onStart: (WaveMode) -> Unit,
    onDescribe: () -> Unit,
    onStop: () -> Unit,
    onDismissMessage: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Waves,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.wave),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    val subtitle = when {
                        mode == null -> null
                        description != null -> stringResource(id = R.string.wave_playing) + " · " + description
                        else -> stringResource(id = R.string.wave_playing)
                    }
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalMutedOnBackground.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (starting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (mode != null) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = stringResource(id = R.string.wave_stop))
                    }
                }
            }

            if (message != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissMessage) { Text(stringResource(id = android.R.string.ok)) }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            WaveMode.entries.forEach { option ->
                WaveOption(
                    title = stringResource(
                        id = when (option) {
                            WaveMode.TASTE -> R.string.wave_taste
                            WaveMode.SIMILAR -> R.string.wave_similar
                            WaveMode.DESCRIPTION -> R.string.wave_description
                        }
                    ),
                    hint = stringResource(
                        id = when (option) {
                            WaveMode.TASTE -> R.string.wave_taste_hint
                            WaveMode.SIMILAR -> R.string.wave_similar_hint
                            WaveMode.DESCRIPTION -> R.string.wave_description_hint
                        }
                    ),
                    running = mode == option,
                    onClick = { if (option == WaveMode.DESCRIPTION) onDescribe() else onStart(option) }
                )
            }
        }
    }
}

@Composable
private fun WaveOption(title: String, hint: String, running: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (running) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = LocalMutedOnBackground.current
            )
        }
    }
}

/** Asks what the stream should sound like; the text is encoded by CLAP and becomes its direction. */
@Composable
private fun DescribeWaveDialog(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.wave_describe_prompt)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(id = R.string.wave_description_hint)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onStart(text.trim()) }) {
                Text(stringResource(id = R.string.wave_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.cancel)) }
        }
    )
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        action()
    }
}

@Composable
private fun SmartAlbumItem(album: SmartAlbum, isActive: Boolean, onClick: () -> Unit) {
    // Highlighted the same way as the folder holding the playing track.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(
            albumId = album.songs.firstOrNull()?.albumId ?: 0L,
            size = 48.dp,
            iconPadding = 8.dp,
            fallbackIcon = Icons.Default.AutoAwesome,
            fallbackTint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            val count = stringResource(id = R.string.songs_count, album.songs.size)
            Text(
                text = if (album.subtitle.isEmpty()) count else "$count · ${album.subtitle}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalMutedOnBackground.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isActive) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = stringResource(id = R.string.now_playing_album),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun PlaylistItem(playlist: Playlist, scannedCount: Int, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val firstSong = playlist.songs.firstOrNull()
        if (firstSong != null) {
            AlbumArt(
                albumId = firstSong.albumId,
                size = 48.dp,
                iconPadding = 8.dp,
                fallbackIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
                fallbackTint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.songs_count, playlist.songs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMutedOnBackground.current
                )
                if (scannedCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = " $scannedCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.delete))
        }
    }
}
