package music.ai.recommend.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import music.ai.recommend.model.Folder
import music.ai.recommend.platform.rememberArtworkPainter
import androidx.compose.foundation.Image

@Composable
fun FolderItem(
    folder: Folder,
    onAddClick: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    // Use artwork from first song as folder icon if available
    val artworkPainter = rememberArtworkPainter(folder.songs.firstOrNull()?.path)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                    onClick()
                    true
                } else false
            }
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (isFocused) 0.7f else 0.4f)
        ),
        border = if (isFocused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(folder.name) },
            supportingContent = { Text("${folder.songs.size} tracks") },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
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
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            trailingContent = {
                IconButton(onClick = onAddClick) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add folder to Playlist")
                }
            }
        )
    }
}
