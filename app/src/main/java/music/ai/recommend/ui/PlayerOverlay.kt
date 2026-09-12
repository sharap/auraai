package music.ai.recommend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import music.ai.recommend.MusicViewModel
import music.ai.recommend.ui.theme.LocalMutedOnSurface

@Composable
fun PlayerOverlay(
    viewModel: MusicViewModel,
    onClick: () -> Unit
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()

    val song = currentSong ?: return

    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArt(
                    albumId = song.albumId,
                    size = 40.dp,
                    fallbackIcon = Icons.Default.MusicNote,
                    fallbackTint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Colours are set explicitly rather than inherited: the ambient content colour
                    // here resolved to a light grey that measured 1.14:1 against the bar.
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalMutedOnSurface.current,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (song.id in scannedIds) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                    PlaybackTimeLabel(viewModel)
                }
                IconButton(onClick = {
                    if (isPlaying) viewModel.pause() else viewModel.resume()
                }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { viewModel.next() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            PlaybackProgressBar(
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * Isolated so that the twice-a-second position tick recomposes one Text instead of the whole
 * overlay — album art, title and buttons included.
 */
@Composable
private fun PlaybackTimeLabel(viewModel: MusicViewModel) {
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    Text(
        text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Reads position and duration inside the progress lambda rather than during composition, so a tick
 * only invalidates the draw phase of the bar itself.
 */
@Composable
private fun PlaybackProgressBar(viewModel: MusicViewModel, modifier: Modifier) {
    val position = viewModel.currentPosition.collectAsState()
    val duration = viewModel.duration.collectAsState()
    LinearProgressIndicator(
        progress = {
            val total = duration.value
            if (total <= 0L) 0f else (position.value.toFloat() / total).coerceIn(0f, 1f)
        },
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
