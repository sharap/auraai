package music.ai.recommend.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.ContentUris
import android.net.Uri
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import music.ai.recommend.MusicViewModel
import music.ai.recommend.R
import music.ai.recommend.model.Song

@Composable
fun SongListScreen(
    viewModel: MusicViewModel,
    title: String,
    songs: List<Song>,
    onBack: () -> Unit
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()
    val favoriteIds by viewModel.favoriteSongIds.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    
    val listState = rememberLazyListState()
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    LaunchedEffect(currentSong) {
        val index = songs.indexOfFirst { it.id == currentSong?.id }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                itemsIndexed(songs, key = { _, song -> song.id }) { _, song ->
                    val isActive = song.id == currentSong?.id
                    val isScanned = song.id in scannedIds
                    val isFavorite = song.id in favoriteIds
                    SongItem(
                        song = song,
                        isActive = isActive,
                        isScanned = isScanned,
                        isFavorite = isFavorite,
                        onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                        onClick = { viewModel.playSong(song, songs) },
                        onLongClick = { selectedSongForMenu = song }
                    )
                }
            }
        }

        if (selectedSongForMenu != null) {
            SongContextMenu(
                song = selectedSongForMenu!!,
                onDismiss = { selectedSongForMenu = null },
                onPlayNext = {
                    viewModel.playNext(selectedSongForMenu!!)
                    selectedSongForMenu = null
                },
                onAddToQueue = {
                    viewModel.addToEndOfQueue(selectedSongForMenu!!)
                    selectedSongForMenu = null
                },
                onAddToPlaylist = {
                    showPlaylistPicker = true
                },
                onCreatePlaylist = {
                    showNewPlaylistDialog = true
                },
                onDelete = {
                    viewModel.deleteSong(selectedSongForMenu!!)
                    selectedSongForMenu = null
                }
            )
        }

        if (showPlaylistPicker && selectedSongForMenu != null) {
            AlertDialog(
                onDismissRequest = { showPlaylistPicker = false },
                title = { Text(stringResource(id = R.string.add_to_playlist)) },
                text = {
                    LazyColumn {
                        items(playlists) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name) },
                                modifier = Modifier.clickable {
                                    viewModel.addSongToPlaylist(playlist.name, selectedSongForMenu!!)
                                    showPlaylistPicker = false
                                    selectedSongForMenu = null
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPlaylistPicker = false }) {
                        Text(stringResource(id = R.string.cancel))
                    }
                }
            )
        }

        if (showNewPlaylistDialog && selectedSongForMenu != null) {
            AlertDialog(
                onDismissRequest = { showNewPlaylistDialog = false },
                title = { Text(stringResource(id = R.string.create_new_playlist)) },
                text = {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text(stringResource(id = R.string.playlist_name)) },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylistWithSongs(newPlaylistName, listOf(selectedSongForMenu!!))
                            showNewPlaylistDialog = false
                            selectedSongForMenu = null
                            newPlaylistName = ""
                        }
                    }) {
                        Text(stringResource(id = R.string.create))
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: Song,
    isActive: Boolean,
    isScanned: Boolean,
    isFavorite: Boolean = false,
    score: Float? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(vertical = 8.dp)
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val albumArtUri = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            song.albumId
        )
        SubcomposeAsyncImage(
            model = albumArtUri,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            error = {
                Icon(
                    imageVector = if (isActive) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(8.dp)
                )
            }
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        
        if (score != null) {
            Text(
                text = "${(score * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        if (isScanned) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "AI Analyzed",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
        }

        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Toggle Favorite",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongContextMenu(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(id = R.string.play_next)) },
                leadingContent = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onPlayNext)
            )
            ListItem(
                headlineContent = { Text(stringResource(id = R.string.add_to_queue)) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onAddToQueue)
            )
            ListItem(
                headlineContent = { Text(stringResource(id = R.string.create_new_playlist)) },
                leadingContent = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onCreatePlaylist)
            )
            ListItem(
                headlineContent = { Text(stringResource(id = R.string.add_to_playlist)) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onAddToPlaylist)
            )
            ListItem(
                headlineContent = { Text(stringResource(id = R.string.delete_from_device)) },
                leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(onClick = onDelete)
            )
        }
    }
}
