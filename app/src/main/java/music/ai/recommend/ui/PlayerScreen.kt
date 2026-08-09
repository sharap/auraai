package music.ai.recommend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import android.content.ContentUris
import android.net.Uri
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import music.ai.recommend.MusicViewModel
import music.ai.recommend.Playlist
import music.ai.recommend.R
import music.ai.recommend.model.Song

@Composable
fun PlayerScreen(
    viewModel: MusicViewModel,
    onClose: () -> Unit
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()
    val shuffleModeEnabled by viewModel.shuffleModeEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val audioSessionId by viewModel.audioSessionId.collectAsState()
    val aiShuffleEnabled by viewModel.aiShuffleEnabled.collectAsState()
    val favoriteIds by viewModel.favoriteSongIds.collectAsState()
    val sleepTimerRemaining by viewModel.sleepTimerRemaining.collectAsState()

    val song = currentSong ?: run {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent) 
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Close")
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sleepTimerRemaining != null) {
                    Text(
                        text = formatTime(sleepTimerRemaining!!),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { showSleepTimerDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Sleep Timer",
                        tint = if (sleepTimerRemaining != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            if (page == 0) {
                PlayerMainContent(
                    currentSong = song,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    shuffleModeEnabled = shuffleModeEnabled,
                    repeatMode = repeatMode,
                    audioSessionId = audioSessionId,
                    scannedIds = scannedIds,
                    aiShuffleEnabled = aiShuffleEnabled,
                    isFavorite = song.id in favoriteIds,
                    onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                    onSeek = { viewModel.seekTo(it) },
                    onToggleShuffle = { viewModel.toggleShuffle() },
                    onToggleAiShuffle = { viewModel.toggleAiShuffle() },
                    onNextRepeatMode = { viewModel.nextRepeatMode() },
                    onPrevious = { viewModel.previous() },
                    onNext = { viewModel.next() },
                    onPlayPause = { if (isPlaying) viewModel.pause() else viewModel.resume() },
                    onPlaySimilar = { viewModel.playSimilar(song) }
                )
            } else {
                QueueList(
                    queue = queue,
                    playlists = playlists,
                    scannedIds = scannedIds,
                    currentSong = song,
                    onSongClick = { s -> viewModel.playSong(s, queue) },
                    onRemove = { index -> viewModel.removeFromQueue(index) },
                    onMove = { from, to -> viewModel.moveQueueItem(from, to) },
                    onSaveNew = { name -> viewModel.createPlaylistWithSongs(name, queue) },
                    onAddToExisting = { name -> viewModel.addSongsToPlaylist(name, queue) },
                    onOverwriteExisting = { name -> viewModel.overwritePlaylist(name, queue) }
                )
            }
        }

        // Pager Indicators
        Row(
            Modifier
                .height(50.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(2) { iteration ->
                val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(color)
                        .size(8.dp)
                )
            }
        }

        if (showSleepTimerDialog) {
            SleepTimerDialog(
                currentRemaining = sleepTimerRemaining,
                onStart = { viewModel.startSleepTimer(it) },
                onStop = { viewModel.stopSleepTimer() },
                onDismiss = { showSleepTimerDialog = false }
            )
        }
    }
}

@Composable
fun PlayerMainContent(
    currentSong: Song,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    audioSessionId: Int?,
    scannedIds: Set<Long>,
    aiShuffleEnabled: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleAiShuffle: () -> Unit,
    onNextRepeatMode: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onPlaySimilar: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var sliderPosition by remember { mutableStateOf<Float?>(null) }
    val displayPosition = sliderPosition ?: currentPosition.toFloat()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            val albumArtUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                currentSong.albumId
            )
            SubcomposeAsyncImage(
                model = albumArtUri,
                contentDescription = null,
                modifier = Modifier
                    .size(240.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                error = {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(120.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                }
            )
            BarVisualizer(
                audioSessionId = audioSessionId,
                isPlaying = isPlaying,
                modifier = Modifier.size(280.dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = currentSong.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (currentSong.id in scannedIds) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "AI Analyzed",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            text = currentSong.artist,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(32.dp))

        Slider(
            value = displayPosition,
            onValueChange = { sliderPosition = it },
            onValueChangeFinished = {
                sliderPosition?.let { onSeek(it.toLong()) }
                sliderPosition = null
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatTime(displayPosition.toLong()))
            Text(text = formatTime(duration))
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleAiShuffle()
            }) {
                Icon(
                    imageVector = Icons.Default.AutoMode,
                    contentDescription = "AI Shuffle",
                    tint = if (aiShuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(imageVector = Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(48.dp))
            }
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlayPause()
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(48.dp)
                )
            }
            IconButton(onClick = onNext) {
                Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(48.dp))
            }
            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleFavorite()
            }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffleModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onNextRepeatMode) {
                val icon = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                    Player.REPEAT_MODE_ALL -> Icons.Default.Repeat
                    else -> Icons.Default.Repeat
                }
                val tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "Repeat",
                        tint = tint
                    )
                }
            }

            IconButton(onClick = onPlaySimilar) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Play Similar",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun SleepTimerDialog(
    currentRemaining: Long?,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.sleep_timer)) },
        text = {
            Column {
                if (currentRemaining != null) {
                    Text(
                        text = stringResource(id = R.string.timer_active, formatTime(currentRemaining)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                val options = listOf(15, 30, 45, 60)
                options.forEach { mins ->
                    ListItem(
                        headlineContent = { Text(stringResource(id = R.string.minutes, mins)) },
                        modifier = Modifier.clickable {
                            onStart(mins)
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {
            if (currentRemaining != null) {
                TextButton(onClick = {
                    onStop()
                    onDismiss()
                }) {
                    Text(stringResource(id = R.string.stop_timer), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.cancel))
            }
        }
    )
}

@Composable
fun QueueList(
    queue: List<Song>,
    playlists: List<Playlist>,
    scannedIds: Set<Long>,
    currentSong: Song?,
    onSongClick: (Song) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSaveNew: (String) -> Unit,
    onAddToExisting: (String) -> Unit,
    onOverwriteExisting: (String) -> Unit
) {
    val listState = rememberLazyListState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var saveMode by remember { mutableStateOf("new") } 

    LaunchedEffect(currentSong) {
        val index = queue.indexOfFirst { it.id == currentSong?.id }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(id = R.string.playback_queue),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            TextButton(onClick = { showSaveDialog = true }) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(id = R.string.save_queue))
            }
        }

        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text(stringResource(id = R.string.save_queue_title)) },
                text = {
                    Column {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { saveMode = "new" }) {
                                RadioButton(selected = saveMode == "new", onClick = { saveMode = "new" })
                                Text(stringResource(id = R.string.new_playlist))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { saveMode = "add" }) {
                                RadioButton(selected = saveMode == "add", onClick = { saveMode = "add" })
                                Text(stringResource(id = R.string.add_to_existing))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { saveMode = "overwrite" }) {
                                RadioButton(selected = saveMode == "overwrite", onClick = { saveMode = "overwrite" })
                                Text(stringResource(id = R.string.overwrite_existing))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        if (saveMode == "new") {
                            OutlinedTextField(
                                value = playlistName,
                                onValueChange = { playlistName = it },
                                label = { Text(stringResource(id = R.string.playlist_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                items(playlists) { playlist ->
                                    ListItem(
                                        headlineContent = { Text(playlist.name) },
                                        modifier = Modifier.clickable {
                                            if (saveMode == "add") {
                                                onAddToExisting(playlist.name)
                                            } else {
                                                onOverwriteExisting(playlist.name)
                                            }
                                            showSaveDialog = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (saveMode == "new") {
                        TextButton(onClick = {
                            if (playlistName.isNotBlank()) {
                                onSaveNew(playlistName)
                                showSaveDialog = false
                                playlistName = ""
                            }
                        }) {
                            Text(stringResource(id = R.string.save))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) {
                        Text(stringResource(id = R.string.cancel))
                    }
                }
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(queue, key = { _, song -> song.id }) { index, song ->
                val isCurrent = song.id == currentSong?.id
                val backgroundColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                val textColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(backgroundColor)
                        .clickable { onSongClick(song) }
                        .padding(12.dp),
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
                            .size(32.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        error = {
                            Icon(
                                imageVector = if (isCurrent) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (song.id in scannedIds) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                    
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Column {
                        IconButton(
                            onClick = { if (index > 0) onMove(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ArrowDropUp, contentDescription = "Move Up")
                        }
                        IconButton(
                            onClick = { if (index < queue.size - 1) onMove(index, index + 1) },
                            enabled = index < queue.size - 1,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Move Down")
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
