package music.ai.recommend.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import music.ai.recommend.MusicViewModel
import music.ai.recommend.PlaybackMode
import music.ai.recommend.ui.components.BarVisualizer
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import music.ai.recommend.platform.rememberArtworkPainter

@Composable
fun PlayerScreen(viewModel: MusicViewModel) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val playbackMode by viewModel.playbackMode.collectAsState()
    val favoritePaths by viewModel.favoriteSongPaths.collectAsState()
    val volume by viewModel.volume.collectAsState()

    val song = currentSong
    val isFavorite = song?.let { favoritePaths.contains(it.path) } ?: false
    val artworkPainter = rememberArtworkPainter(song?.path)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (song == null) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Select a song to play", style = MaterialTheme.typography.bodyLarge)
        } else {
            // Album Art Placeholder / Real Art
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (artworkPainter != null) {
                        Image(
                            painter = artworkPainter,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(120.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                    }
                }
                BarVisualizer(
                    isPlaying = isPlaying,
                    modifier = Modifier.size(280.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(32.dp))

            Slider(
                value = currentPosition.toFloat(),
                onValueChange = { viewModel.seekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = formatTime(currentPosition), style = MaterialTheme.typography.labelSmall)
                Text(text = formatTime(duration), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = { viewModel.cyclePlaybackMode() }) {
                    val icon = when (playbackMode) {
                        PlaybackMode.RepeatQueue -> Icons.Default.Repeat
                        PlaybackMode.StopAfterQueue -> Icons.Default.FormatListNumbered
                        PlaybackMode.RepeatOne -> Icons.Default.RepeatOne
                        PlaybackMode.StopAfterTrack -> Icons.Default.PlayArrow
                        PlaybackMode.Shuffle -> Icons.Default.Shuffle
                    }
                    val tint = if (playbackMode == PlaybackMode.StopAfterTrack) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                    Icon(icon, contentDescription = "Mode", tint = tint)
                }
                
                IconButton(onClick = { viewModel.previous() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null, modifier = Modifier.size(32.dp))
                }
                
                FloatingActionButton(
                    onClick = { if (isPlaying) viewModel.pause() else viewModel.resume() },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                IconButton(onClick = { viewModel.next() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(32.dp))
                }

                IconButton(onClick = { viewModel.toggleFavorite(song) }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Reads the same state MPRIS writes, so a change made from the panel widget or a
            // media key moves this slider too.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.toggleMute() }) {
                    Icon(
                        imageVector = when {
                            volume == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                            volume < 34 -> Icons.AutoMirrored.Filled.VolumeMute
                            volume < 67 -> Icons.AutoMirrored.Filled.VolumeDown
                            else -> Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = if (volume == 0) "Unmute" else "Mute",
                        tint = if (volume == 0) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { viewModel.setVolume(it.roundToInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$volume%",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    // Fixed width, so the slider does not shift as the number gains a digit.
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}
