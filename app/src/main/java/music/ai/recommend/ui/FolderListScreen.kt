package music.ai.recommend.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import music.ai.recommend.ui.theme.LocalMutedOnBackground
import music.ai.recommend.MusicViewModel
import music.ai.recommend.R
import music.ai.recommend.model.Folder

@Composable
fun FolderListScreen(
    viewModel: MusicViewModel,
    onFolderClick: (String) -> Unit
) {
    val folders by viewModel.folders.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()
    val scanCounts by viewModel.folderScanCounts.collectAsState()
    val activeFolderName = currentSong?.folderName
    val currentSongId = currentSong?.id

    val search = rememberSongSearch(viewModel)
    var selectedFolderForMenu by remember { mutableStateOf<Folder?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        SongSearchField(
            state = search,
            placeholder = stringResource(id = R.string.search_hint),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )

        Box(modifier = Modifier.weight(1f)) {
            if (search.isActive) {
                val plainTitle = stringResource(id = R.string.search_results)
                val aiTitle = stringResource(id = R.string.ai_recommendations)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    songSearchResults(
                        state = search,
                        plainTitle = plainTitle,
                        aiTitle = aiTitle,
                        currentSongId = currentSongId,
                        scannedIds = scannedIds,
                        onPlayPlain = { song, list -> viewModel.playSong(song, list) },
                        onPlayAi = { song, list -> viewModel.playSong(song, list) }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(folders, key = { it.name }) { folder ->
                        FolderItem(
                            folder = folder,
                            isActive = folder.name == activeFolderName,
                            scannedCount = scanCounts[folder.name] ?: 0,
                            onClick = { onFolderClick(folder.name) },
                            onLongClick = { selectedFolderForMenu = folder }
                        )
                    }
                }
            }
        }
    }

    selectedFolderForMenu?.let { selected ->
        FolderContextMenu(
            folder = selected,
            onDismiss = { selectedFolderForMenu = null },
            onPlayNext = {
                // One batched controller call instead of one round trip per song.
                viewModel.playAllNext(selected.songs)
                selectedFolderForMenu = null
            },
            onAddToQueue = {
                viewModel.addAllToEndOfQueue(selected.songs)
                selectedFolderForMenu = null
            },
            onCreatePlaylist = { showNewPlaylistDialog = true },
            onAddToExistingPlaylist = { showPlaylistPicker = true }
        )
    }

    if (showNewPlaylistDialog && selectedFolderForMenu != null) {
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
                    val selected = selectedFolderForMenu
                    if (newPlaylistName.isNotBlank() && selected != null) {
                        viewModel.createPlaylistWithSongs(newPlaylistName, selected.songs)
                        showNewPlaylistDialog = false
                        selectedFolderForMenu = null
                        newPlaylistName = ""
                    }
                }) {
                    Text(stringResource(id = R.string.create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylistDialog = false }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }

    if (showPlaylistPicker && selectedFolderForMenu != null) {
        AlertDialog(
            onDismissRequest = { showPlaylistPicker = false },
            title = { Text(stringResource(id = R.string.add_to_playlist)) },
            text = {
                LazyColumn {
                    items(playlists, key = { it.name }) { playlist ->
                        ListItem(
                            headlineContent = { Text(playlist.name) },
                            modifier = Modifier.clickable {
                                selectedFolderForMenu?.let { viewModel.addSongsToPlaylist(playlist.name, it.songs) }
                                showPlaylistPicker = false
                                selectedFolderForMenu = null
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderItem(
    folder: Folder,
    isActive: Boolean,
    scannedCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp)
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val firstSong = folder.songs.firstOrNull()
        if (firstSong != null) {
            AlbumArt(
                albumId = firstSong.albumId,
                size = 48.dp,
                iconPadding = 8.dp,
                fallbackIcon = Icons.Default.Folder,
                fallbackTint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.songs_count, folder.songs.size),
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderContextMenu(
    folder: Folder,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onAddToExistingPlaylist: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = folder.name,
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
                modifier = Modifier.clickable(onClick = onAddToExistingPlaylist)
            )
        }
    }
}
