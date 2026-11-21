package music.ai.recommend.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import music.ai.recommend.MusicViewModel
import music.ai.recommend.ui.components.SongItem
import music.ai.recommend.model.Song

@Composable
fun QueueScreen(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier,
    onSaveQueue: (List<Song>) -> Unit = {},
    onShowInfo: (Song) -> Unit = {}
) {
    val queue by viewModel.queue.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val favoritePaths by viewModel.favoriteSongPaths.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Playback Queue", style = MaterialTheme.typography.titleMedium)
            Row {
                if (queue.isNotEmpty()) {
                    IconButton(onClick = { onSaveQueue(queue) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Save Queue to Playlist")
                    }
                }
                IconButton(onClick = { viewModel.clearQueue() }) {
                    Icon(Icons.Default.ClearAll, contentDescription = "Clear Queue")
                }
            }
        }

        if (queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    itemsIndexed(queue) { index, song ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    onClick = { viewModel.playSong(song) }
                                )
                            }
                            IconButton(onClick = { viewModel.removeFromQueue(index) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    modifier = Modifier.fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(scrollState = listState)
                )
            }
        }
    }
}
