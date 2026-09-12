package music.ai.recommend.ui

import android.content.ContentUris
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage

private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

/**
 * Album artwork with an icon fallback.
 *
 * The fallback icon sits behind the image rather than in a `SubcomposeAsyncImage` error slot:
 * subcomposition costs an extra measure/layout pass per item, which is exactly the wrong trade in a
 * list row that scrolls. A failed or pending load simply draws nothing and lets the icon show
 * through.
 */
@Composable
fun AlbumArt(
    albumId: Long,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    fallbackIcon: ImageVector = Icons.Default.MusicNote,
    fallbackTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconPadding: Dp = size / 4,
    background: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val model = remember(albumId) { albumArtUri(albumId) }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = fallbackTint,
            modifier = Modifier.size(size - iconPadding * 2)
        )
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Crop
        )
    }
}
