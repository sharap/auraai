package music.ai.recommend.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import music.ai.recommend.ui.theme.LocalMutedOnBackground
import music.ai.recommend.MusicViewModel
import music.ai.recommend.R
import music.ai.recommend.model.Song

@Composable
fun SongListScreen(
    viewModel: MusicViewModel,
    title: String,
    songs: List<Song>,
    /**
     * The playlist these songs are being shown as, when they are one.
     *
     * The same screen shows an album, a folder and a playlist, and nothing in a list of songs says
     * which — so without this a track's menu cannot offer to take it out of where it is being read
     * from. Null for the other two, and then the menu has no such item.
     */
    fromPlaylist: String? = null,
    onBack: () -> Unit
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()
    val favoriteIds by viewModel.favoriteSongIds.collectAsState()
    val playlists by viewModel.playlists.collectAsState()

    val listState = rememberLazyListState()
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }

    // Changing the order is only a thing where there is an order to change. The favourites are a
    // set of ids shown in library order and have none of their own, so the list of them is read
    // and not arranged.
    val favouritesName = stringResource(id = R.string.favorites)
    val canReorder = fromPlaylist != null && fromPlaylist != favouritesName
    var reordering by remember(fromPlaylist) { mutableStateOf(false) }
    // The order being dragged, which is this screen's for the length of the gesture and the
    // playlist's again as soon as it is let go.
    var ordered by remember { mutableStateOf(songs) }
    // Saved when the finger is lifted, when the mode is left, and when the screen goes away. The
    // lifting is not enough on its own: a gesture can also end by having its row scrolled out of
    // the list from under it, and a gesture that ends that way calls nothing back. Asking more
    // than once costs nothing, because an order that is already the order is not recorded.
    val saveOrder = { if (fromPlaylist != null) viewModel.reorderPlaylist(fromPlaylist, ordered) }
    val reorder = rememberDragToReorder(
        listState = listState,
        // By the track and not by its place in the list, which is the thing being changed.
        indexOf = { key -> ordered.indexOfFirst { it.id == key } },
        onMove = { from, to ->
            ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
        },
        onDropped = { saveOrder() }
    )
    LaunchedEffect(songs, reordering) {
        // The playlist as it now stands, unless a finger is in the middle of saying otherwise.
        if (reorder.dragging == null) ordered = songs
    }
    DisposableEffect(Unit) {
        onDispose { if (reordering) saveOrder() }
    }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    val search = rememberSongSearch(viewModel, scope = songs)
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val currentSongId = currentSong?.id
    LaunchedEffect(currentSongId) {
        // While searching the list shows results, and the index would point at the wrong row.
        // While arranging it, scrolling away from what the owner is holding is worse still.
        if (currentSongId == null || search.isActive || reordering) return@LaunchedEffect
        val index = songs.indexOfFirst { it.id == currentSongId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.back))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (canReorder) {
                IconButton(onClick = {
                    // Any gesture still in the air is ended first, so that what it did is saved
                    // and the row it was holding is let go of.
                    reorder.stop()
                    reordering = !reordering
                    if (reordering) {
                        searchOpen = false
                        search.clear()
                    } else {
                        saveOrder()
                    }
                }) {
                    Icon(
                        imageVector = if (reordering) Icons.Default.Check else Icons.Default.SwapVert,
                        contentDescription = stringResource(
                            id = if (reordering) R.string.done else R.string.reorder_tracks
                        )
                    )
                }
            }
            if (!reordering) {
                IconButton(onClick = {
                    if (searchOpen) search.clear()
                    searchOpen = !searchOpen
                }) {
                    Icon(
                        imageVector = if (searchOpen) Icons.Default.SearchOff else Icons.Default.Search,
                        contentDescription = stringResource(id = R.string.search_in_album)
                    )
                }
            }
        }

        if (searchOpen) {
            SongSearchField(
                state = search,
                placeholder = stringResource(id = R.string.search_in_hint, title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focusRequester)
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }

        val searchInTitle = stringResource(id = R.string.search_results_in, title)
        val aiLibraryTitle = stringResource(id = R.string.ai_recommendations_library)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp)
        ) {
            if (reordering) {
                itemsIndexed(ordered, key = { _, song -> song.id }) { index, song ->
                    val held = reorder.dragging == index
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            // The held row is drawn where the finger has it, above the others and
                            // without the placement animation the rest use to slide out of its way
                            // — those two would be describing the same row's position at once.
                            .zIndex(if (held) 1f else 0f)
                            .graphicsLayer { translationY = reorder.offsetOf(index) }
                            .then(if (held) Modifier else Modifier.animateItem())
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SongItem(
                                song = song,
                                isActive = song.id == currentSongId,
                                isScanned = song.id in scannedIds,
                                isFavorite = song.id in favoriteIds,
                                onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                                onClick = { viewModel.playSong(song, ordered) },
                                onLongClick = { selectedSongForMenu = song }
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = stringResource(id = R.string.reorder_tracks),
                            tint = LocalMutedOnBackground.current,
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .reorderHandle(reorder, song.id)
                        )
                    }
                }
            } else if (search.isActive) {
                songSearchResults(
                    state = search,
                    plainTitle = searchInTitle,
                    aiTitle = aiLibraryTitle,
                    currentSongId = currentSongId,
                    scannedIds = scannedIds,
                    // A track found here plays on through the album it was found in.
                    onPlayPlain = { song, _ -> viewModel.playSong(song, songs) },
                    onPlayAi = { song, list -> viewModel.playSong(song, list) }
                )
            } else {
                items(songs, key = { it.id }) { song ->
                    SongItem(
                        song = song,
                        isActive = song.id == currentSongId,
                        isScanned = song.id in scannedIds,
                        isFavorite = song.id in favoriteIds,
                        onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                        onClick = { viewModel.playSong(song, songs) },
                        onLongClick = { selectedSongForMenu = song }
                    )
                }
            }
        }

        selectedSongForMenu?.let { selected ->
            SongContextMenu(
                song = selected,
                onDismiss = { selectedSongForMenu = null },
                onPlayNext = {
                    viewModel.playNext(selected)
                    selectedSongForMenu = null
                },
                onAddToQueue = {
                    viewModel.addToEndOfQueue(selected)
                    selectedSongForMenu = null
                },
                onAddToPlaylist = { showPlaylistPicker = true },
                onCreatePlaylist = { showNewPlaylistDialog = true },
                onRemoveFromPlaylist = fromPlaylist?.let { name ->
                    {
                        viewModel.removeSongFromPlaylist(name, selected)
                        selectedSongForMenu = null
                    }
                },
                onDelete = {
                    viewModel.deleteSong(selected)
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
                        items(playlists, key = { it.name }) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name) },
                                modifier = Modifier.clickable {
                                    selectedSongForMenu?.let { viewModel.addSongToPlaylist(playlist.name, it) }
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
                        val selected = selectedSongForMenu
                        if (newPlaylistName.isNotBlank() && selected != null) {
                            viewModel.createPlaylistWithSongs(newPlaylistName, listOf(selected))
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
        AlbumArt(
            albumId = song.albumId,
            size = 48.dp,
            iconPadding = 8.dp,
            fallbackIcon = if (isActive) Icons.Default.PlayArrow else Icons.Default.MusicNote,
            fallbackTint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = LocalMutedOnBackground.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
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
    /** Offered only where there is a playlist to take the track out of. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
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
            if (onRemoveFromPlaylist != null) {
                ListItem(
                    headlineContent = { Text(stringResource(id = R.string.remove_from_playlist)) },
                    leadingContent = { Icon(Icons.Default.PlaylistRemove, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onRemoveFromPlaylist)
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(id = R.string.delete_from_device)) },
                leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(onClick = onDelete)
            )
        }
    }
}
