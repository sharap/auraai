package music.ai.recommend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import music.ai.recommend.ui.theme.AiMusicTheme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh

import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.draw.clip
import music.ai.recommend.platform.rememberArtworkPainter
import music.ai.recommend.ui.screens.*
import music.ai.recommend.ui.components.FolderItem
import music.ai.recommend.ui.components.SongItem
import music.ai.recommend.platform.rememberLocalImagePainter
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.onClick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    /** Brings the window forward; MPRIS clients send Raise when their widget is clicked. */
    onRaise: () -> Unit = {}
) {
    val viewModel = remember { MusicViewModel() }

    DisposableEffect(viewModel, onRaise) {
        viewModel.onRaiseRequested = onRaise
        // Here rather than in the ViewModel's constructor: by now every field it reads is built.
        viewModel.startRemoteControl()
        onDispose { viewModel.onRaiseRequested = null }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.release()
        }
    }

    // The sync folder is read whenever the window comes to the front, so that what the other
    // device heard is already counted by the time the owner starts a wave or opens a playlist.
    // Focus rather than a window listener: it covers being restored from the taskbar and being
    // switched back to, and it is true at the first composition, so opening the app counts. The
    // pass itself refuses to read the folder more than once a minute.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (windowFocused) viewModel.syncOnOpen() else viewModel.syncOnLeave()
    }

    val folders by viewModel.folders.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()
// ...
    val isScanning by viewModel.isScanning.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val currentSection by viewModel.currentSection.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val backgroundImageUri by viewModel.backgroundImageUri.collectAsState()
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val listState = rememberLazyListState()
    
    val backgroundPainter = rememberLocalImagePainter(backgroundImageUri)
    var selectedTab by remember { mutableIntStateOf(0) }
    
    var songsToAddToPlaylist by remember { mutableStateOf<List<music.ai.recommend.model.Song>?>(null) }
    var songInfoToShow by remember { mutableStateOf<music.ai.recommend.model.Song?>(null) }

    val mainFocusRequester = remember { FocusRequester() }
    val queueFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }

    AiMusicTheme(darkTheme = isDarkTheme) {
        val backgroundColor = if (backgroundImageUri != null) Color.Transparent else MaterialTheme.colorScheme.background
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        if ((keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && keyEvent.key == Key.F) {
                            viewModel.setSearchActive(true)
                            return@onPreviewKeyEvent true
                        }
                        
                        if (keyEvent.key == Key.Escape) {
                            if (isSearchActive) {
                                viewModel.setSearchActive(false)
                                mainFocusRequester.requestFocus()
                            } else if (selectedFolder != null) {
                                viewModel.selectFolder(null)
                                mainFocusRequester.requestFocus()
                            } else if (selectedPlaylist != null) {
                                viewModel.selectPlaylist(null)
                                mainFocusRequester.requestFocus()
                            }
                            return@onPreviewKeyEvent true
                        }

                        when (keyEvent.key) {
                            Key.Spacebar -> {
                                viewModel.togglePlayPause()
                                true
                            }
                            Key.DirectionLeft -> {
                                viewModel.seekRelative(-5000)
                                true
                            }
                            Key.DirectionRight -> {
                                viewModel.seekRelative(5000)
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                .focusRequester(mainFocusRequester)
                .focusable(),
            color = backgroundColor
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (backgroundPainter != null) {
                    Image(
                        painter = backgroundPainter,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = backgroundAlpha },
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.4f))
                    )
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWide = maxWidth > 1100.dp
                    val showTabs = maxWidth < 900.dp && currentSection == AppSection.Folders

                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                            header = {
                                IconButton(onClick = { viewModel.loadMusic() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            }
                        ) {
                            NavigationRailItem(
                                selected = currentSection == AppSection.Playlists,
                                onClick = { viewModel.setSection(AppSection.Playlists) },
                                icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                                label = { Text("Playlists") }
                            )
                            NavigationRailItem(
                                selected = currentSection == AppSection.Folders,
                                onClick = { viewModel.setSection(AppSection.Folders) },
                                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                label = { Text("Music") }
                            )
                            NavigationRailItem(
                                selected = currentSection == AppSection.Settings,
                                onClick = { viewModel.setSection(AppSection.Settings) },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") }
                            )
                        }

                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (showTabs) {
                                PrimaryTabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
                                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Music") })
                                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Queue") })
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (showTabs && selectedTab == 1) {
                                    QueueScreen(
                                        viewModel, 
                                        modifier = Modifier.focusRequester(queueFocusRequester),
                                        onSaveQueue = { songsToAddToPlaylist = it },
                                        onShowInfo = { songInfoToShow = it }
                                    )
                                } else {
                                    MainContentArea(
                                        viewModel, 
                                        modifier = Modifier.focusRequester(mainFocusRequester),
                                        currentSection = currentSection,
                                        selectedFolder = selectedFolder,
                                        folders = folders,
                                        scannedIds = scannedIds,
                                        isScanning = isScanning,
                                        listState = listState,
                                        currentSong = currentSong,
                                        searchFocusRequester = searchFocusRequester,
                                        onAddSongs = { songsToAddToPlaylist = it },
                                        onShowInfo = { songInfoToShow = it }
                                    )
                                }
                            }
                        }

                        if (isWide) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.1f))
                            ) {
                                QueueScreen(
                                    viewModel, 
                                    modifier = Modifier.focusRequester(queueFocusRequester),
                                    onSaveQueue = { songsToAddToPlaylist = it },
                                    onShowInfo = { songInfoToShow = it }
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(400.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.15f))
                        ) {
                            PlayerScreen(viewModel)
                        }
                    }
                }
                
                if (songsToAddToPlaylist != null) {
                    PlaylistSelectorDialog(
                        viewModel = viewModel,
                        songs = songsToAddToPlaylist!!,
                        onDismiss = { songsToAddToPlaylist = null }
                    )
                }
                
                if (songInfoToShow != null) {
                    SongInfoDialog(
                        song = songInfoToShow!!,
                        onDismiss = { songInfoToShow = null }
                    )
                }
            }
        }
    }
    
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        mainFocusRequester.requestFocus()
    }
}

@Composable
fun SongInfoDialog(song: music.ai.recommend.model.Song, onDismiss: () -> Unit) {
    val artworkPainter = rememberArtworkPainter(song.path)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song Information") },
        text = {
            Column(modifier = Modifier.width(400.dp).padding(8.dp)) {
                if (artworkPainter != null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Image(
                            painter = artworkPainter,
                            contentDescription = null,
                            modifier = Modifier
                                .size(120.dp)
                                .clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                InfoRow("Title", song.title)
                InfoRow("Artist", song.artist)
                InfoRow("Album", song.album)
                if (song.year.isNotBlank()) InfoRow("Year", song.year)
                InfoRow("Duration", formatDuration(song.duration))
                InfoRow("Size", formatSize(song.size))
                InfoRow("Path", song.path)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb > 1) "%.2f MB".format(mb) else "%.2f KB".format(kb)
}

@Composable
fun PlaylistSelectorDialog(
    viewModel: MusicViewModel,
    songs: List<music.ai.recommend.model.Song>,
    onDismiss: () -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    var showCreateFromAdd by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist (${songs.size} items)") },
        text = {
            Column {
                ListItem(
                    modifier = Modifier.clickable { showCreateFromAdd = true },
                    headlineContent = { Text("Create New Playlist", color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                )
                
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp).graphicsLayer { alpha = 0.2f }
                )

                if (playlists.isEmpty()) {
                    Text("No playlists yet.", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(playlists) { playlist ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    songs.forEach { song ->
                                        viewModel.addSongToPlaylist(playlist, song)
                                    }
                                    onDismiss()
                                },
                                headlineContent = { Text(playlist.name) },
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showCreateFromAdd) {
        AlertDialog(
            onDismissRequest = { showCreateFromAdd = false },
            title = { Text("New Playlist") },
            text = {
                TextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        viewModel.createPlaylist(newPlaylistName)
                        newPlaylistName = ""
                        showCreateFromAdd = false
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFromAdd = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainContentArea(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier,
    currentSection: AppSection,
    selectedFolder: music.ai.recommend.model.Folder?,
    folders: List<music.ai.recommend.model.Folder>,
    scannedIds: Set<String>,
    isScanning: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    currentSong: music.ai.recommend.model.Song?,
    searchFocusRequester: FocusRequester,
    onAddSongs: (List<music.ai.recommend.model.Song>) -> Unit,
    onShowInfo: (music.ai.recommend.model.Song) -> Unit
) {
    val favoritePaths by viewModel.favoriteSongPaths.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isAiSearchEnabled by viewModel.isAiSearchEnabled.collectAsState()
    val aiSearchResults by viewModel.aiSearchResults.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                            placeholder = { Text(if (isAiSearchEnabled) "AI Search by description..." else "Search...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            trailingIcon = {
                                FilterChip(
                                    selected = isAiSearchEnabled,
                                    onClick = { viewModel.toggleAiSearch() },
                                    label = { Text("AI") },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setSearchActive(false) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    }
                )
            } else {
// ...
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Text(
                            when {
                                selectedFolder != null -> selectedFolder.name
                                selectedPlaylist != null -> selectedPlaylist!!.name
                                currentSection == AppSection.Folders -> "Folders"
                                currentSection == AppSection.Playlists -> "Playlists"
                                currentSection == AppSection.Settings -> "Settings"
                                else -> "Folders"
                            }
                        )
                    },
                    navigationIcon = {
                        if (selectedFolder != null) {
                            IconButton(onClick = { viewModel.selectFolder(null) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        } else if (selectedPlaylist != null) {
                            IconButton(onClick = { viewModel.selectPlaylist(null) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (currentSection == AppSection.Folders || currentSection == AppSection.Playlists) {
                            IconButton(onClick = { viewModel.setSearchActive(true) }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (currentSection) {
                AppSection.Playlists -> PlaylistListScreen(viewModel, onShowInfo = onShowInfo)
                AppSection.Settings -> SettingsScreen(viewModel)
                AppSection.Folders -> {
                    if (selectedFolder == null) {
                        if (isScanning && folders.isEmpty()) {
                            CircularProgressIndicator(modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                        } else if (folders.isEmpty()) {
                            Text("No music found", modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                        } else {
                            Row(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    contentPadding = PaddingValues(8.dp)
                                ) {
                                    if (!isSearchActive) {
                                        item {
                                            var isAllTracksFocused by remember { mutableStateOf(false) }
                                            val allSongs = folders.flatMap { it.songs }
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                    .onFocusChanged { isAllTracksFocused = it.isFocused }
                                                    .focusable()
                                                    .onKeyEvent { 
                                                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                                                            viewModel.selectFolder(music.ai.recommend.model.Folder("All Tracks", allSongs))
                                                            viewModel.setSearchActive(false)
                                                            true
                                                        } else false
                                                    }
                                                    .clickable { 
                                                        viewModel.selectFolder(music.ai.recommend.model.Folder("All Tracks", allSongs))
                                                        viewModel.setSearchActive(false)
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (isAllTracksFocused) 0.8f else 0.5f)
                                                ),
                                                border = if (isAllTracksFocused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                            ) {
                                                ListItem(
                                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                                    headlineContent = { Text("All Tracks") },
                                                    supportingContent = { Text("${allSongs.size} tracks") },
                                                    leadingContent = { Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                                )
                                            }
                                        }

                                        item {
                                            var isFavFocused by remember { mutableStateOf(false) }
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                    .onFocusChanged { isFavFocused = it.isFocused }
                                                    .focusable()
                                                    .onKeyEvent { 
                                                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                                                            val favs = viewModel.getFavoriteSongs()
                                                            if (favs.isNotEmpty()) {
                                                                viewModel.selectPlaylist(music.ai.recommend.model.Playlist(-1, "Favorites", favs))
                                                                viewModel.setSection(AppSection.Playlists)
                                                                viewModel.setSearchActive(false)
                                                            }
                                                            true
                                                        } else false
                                                    }
                                                    .clickable { 
                                                        val favs = viewModel.getFavoriteSongs()
                                                        if (favs.isNotEmpty()) {
                                                            viewModel.selectPlaylist(music.ai.recommend.model.Playlist(-1, "Favorites", favs))
                                                            viewModel.setSection(AppSection.Playlists)
                                                            viewModel.setSearchActive(false)
                                                        }
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isFavFocused) 0.8f else 0.5f)
                                                ),
                                                border = if (isFavFocused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                            ) {
                                                ListItem(
                                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                                    headlineContent = { Text("Favorites") },
                                                    supportingContent = { Text("${favoritePaths.size} tracks") },
                                                    leadingContent = { Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.Red) }
                                                )
                                            }
                                        }
                                    }

                                    // Root view (list of folders)
                                    val foldersToShow = if (isSearchActive && !isAiSearchEnabled) {
                                        folders.filter { it.name.contains(searchQuery, ignoreCase = true) }
                                    } else {
                                        folders
                                    }

                                    if (isAiSearchEnabled && searchQuery.length > 2) {
                                        // Already filtered, ranked and capped by the ViewModel:
                                        // re-sorting here by the displayed percentage would undo
                                        // the ordering, since strong matches all saturate at 99%.
                                        val rankedSongs = aiSearchResults

                                        items(rankedSongs) { (song, rank) ->
                                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                Box(modifier = Modifier.weight(1f)) {
                                                    SongItem(
                                                        song = song,
                                                        isActive = song.id == currentSong?.id,
                                                        isFavorite = favoritePaths.contains(song.path),
                                                        isScanned = true,
                                                        onFavoriteClick = { viewModel.toggleFavorite(song) },
                                                        onPlayNext = { viewModel.playNext(song) },
                                                        onAddToEnd = { viewModel.addToEndOfQueue(song) },
                                                        onSmartPlaylist = { viewModel.createSmartPlaylist(song) },
                                                        onDelete = { viewModel.deleteSong(song) },
                                                        onShowInfo = { onShowInfo(song) },
                                                        onClick = { viewModel.playSong(song, rankedSongs.map { it.song }) }
                                                    )
                                                }
                                                Text(
                                                    "${(rank * 100).toInt()}%", 
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(end = 8.dp),
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    } else {
                                        items(foldersToShow) { folder ->
                                            FolderItem(
                                                folder = folder,
                                                onAddClick = { onAddSongs(folder.songs) },
                                                onClick = { 
                                                    viewModel.selectFolder(folder)
                                                    viewModel.setSearchActive(false)
                                                }
                                            )
                                        }
                                    }
                                }
                                VerticalScrollbar(
                                    modifier = Modifier.fillMaxHeight(),
                                    adapter = rememberScrollbarAdapter(scrollState = listState)
                                )
                            }
                        }
                    } else {
                        val songListState = rememberLazyListState()
                        val songsToShow = if (isSearchActive) {
                            selectedFolder.songs.filter { 
                                it.title.contains(searchQuery, ignoreCase = true) || 
                                it.artist.contains(searchQuery, ignoreCase = true) 
                            }
                        } else {
                            selectedFolder.songs
                        }
                        
                        Row(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = songListState,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                items(songsToShow) { song ->
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            SongItem(
                                                song = song,
                                                isActive = song.id == currentSong?.id,
                                                isFavorite = favoritePaths.contains(song.path),
                                                isScanned = scannedIds.contains(song.path),
                                                onFavoriteClick = { viewModel.toggleFavorite(song) },
                                                onPlayNext = { viewModel.playNext(song) },
                                                onAddToEnd = { viewModel.addToEndOfQueue(song) },
                                                onSmartPlaylist = { viewModel.createSmartPlaylist(song) },
                                                onDelete = { viewModel.deleteSong(song) },
                                                onShowInfo = { onShowInfo(song) },
                                                onClick = { viewModel.playSong(song, songsToShow) }
                                            )
                                        }
                                        IconButton(onClick = { onAddSongs(listOf(song)) }) {
                                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add to Playlist")
                                        }
                                    }
                                }
                            }
                            VerticalScrollbar(
                                modifier = Modifier.fillMaxHeight(),
                                adapter = rememberScrollbarAdapter(scrollState = songListState)
                            )
                        }
                    }
                }
            }
        }
    }
}
