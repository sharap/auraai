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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import music.ai.recommend.ui.theme.LocalMutedOnBackground
import music.ai.recommend.MusicViewModel
import music.ai.recommend.Playlist
import music.ai.recommend.R
import music.ai.recommend.ai.SmartAlbum

@Composable
fun PlaylistListScreen(
    viewModel: MusicViewModel,
    onPlaylistClick: (String) -> Unit,
    onSmartAlbumClick: (String) -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    val scanCounts by viewModel.playlistScanCounts.collectAsState()
    val smartAlbums by viewModel.smartAlbums.collectAsState()
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
