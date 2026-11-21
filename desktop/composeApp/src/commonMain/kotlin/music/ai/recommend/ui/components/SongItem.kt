package music.ai.recommend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import music.ai.recommend.model.Song
import androidx.compose.foundation.onClick
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import music.ai.recommend.platform.rememberArtworkPainter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: Song,
    isActive: Boolean,
    isFavorite: Boolean = false,
    isScanned: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToEnd: (() -> Unit)? = null,
    onSmartPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onShowInfo: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val artworkPainter = rememberArtworkPainter(song.path)

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.Enter -> {
                                onClick()
                                true
                            }
                            Key.Delete -> {
                                if (keyEvent.isCtrlPressed) {
                                    onDelete?.invoke()
                                    true
                                } else false
                            }
                            Key.L -> {
                                onFavoriteClick?.invoke()
                                true
                            }
                            Key.I -> {
                                if (keyEvent.isCtrlPressed) {
                                    onShowInfo?.invoke()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else false
                }
                .onClick(
                    matcher = PointerMatcher.Primary,
                    onClick = onClick
                )
                .onClick(
                    matcher = PointerMatcher.mouse(PointerButton.Secondary),
                    onClick = { showMenu = true }
                ),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    isFocused -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                }
            ),
            border = when {
                isFocused -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                isActive -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                else -> null
            }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
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
                            imageVector = if (isActive) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                if (onPlayNext != null) {
                    IconButton(onClick = onPlayNext) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "Play Next", modifier = Modifier.size(20.dp))
                    }
                }

                if (onFavoriteClick != null) {
                    IconButton(onClick = onFavoriteClick) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Play Next") },
                onClick = { onPlayNext?.invoke(); showMenu = false },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text("Add to End of Queue") },
                onClick = { onAddToEnd?.invoke(); showMenu = false },
                leadingIcon = { Icon(Icons.Default.Queue, contentDescription = null) }
            )
            if (isScanned && onSmartPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Create Smart Playlist", color = MaterialTheme.colorScheme.primary) },
                    onClick = { onSmartPlaylist.invoke(); showMenu = false },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                )
            }
            DropdownMenuItem(
                text = { Text("Song Info") },
                onClick = { onShowInfo?.invoke(); showMenu = false },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete Track", color = MaterialTheme.colorScheme.error) },
                onClick = { onDelete?.invoke(); showMenu = false },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
            )
        }
    }
}
