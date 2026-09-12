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
    val aiSearchResults by viewModel.aiSearchResults.collectAsState()
    val regularSearchResults by viewModel.regularSearchResults.collectAsState()
    val isAiSearching by viewModel.isAiSearching.collectAsState()
    val aiSearchNeedsModel by viewModel.aiSearchNeedsModel.collectAsState()
    val activeFolderName = currentSong?.folderName
    val currentSongId = currentSong?.id

    var searchQuery by remember { mutableStateOf("") }
    var selectedFolderForMenu by remember { mutableStateOf<Folder?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                viewModel.aiSearch(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text(stringResource(id = R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
            trailingIcon = {
                if (isAiSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        viewModel.aiSearch("")
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(id = R.string.clear_search))
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium
        )

        Box(modifier = Modifier.weight(1f)) {
            val results = aiSearchResults
            val regResults = regularSearchResults

            if (results != null || regResults != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (!regResults.isNullOrEmpty()) {
                        item(key = "header_plain") {
                            Text(
                                text = stringResource(id = R.string.search_results),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        items(regResults, key = { "plain_${it.id}" }) { song ->
                            SongItem(
                                song = song,
                                isActive = song.id == currentSongId,
                                isScanned = song.id in scannedIds,
                                onClick = { viewModel.playSong(song, regResults) },
                                onLongClick = { }
                            )
                        }
                    }

                    if (aiSearchNeedsModel) {
                        item(key = "ai_needs_model") {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(id = R.string.ai_search_needs_model),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalMutedOnBackground.current
                            )
                        }
                    }

                    if (!results.isNullOrEmpty()) {
                        item(key = "header_ai") {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(id = R.string.ai_recommendations),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        items(results, key = { "ai_${it.song.id}" }) { scoredSong ->
                            SongItem(
                                song = scoredSong.song,
                                isActive = scoredSong.song.id == currentSongId,
                                isScanned = true,
                                score = scoredSong.score,
                                onClick = { viewModel.playSong(scoredSong.song, results.map { it.song }) },
                                onLongClick = { }
                            )
                        }
                    }
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
