package music.ai.recommend.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

actual fun pickDirectory(): String? {
    return null
}

actual fun pickImageFile(): String? {
    return null
}

@Composable
actual fun rememberLocalImagePainter(path: String?): Painter? {
    return null
}

@Composable
actual fun rememberArtworkPainter(songPath: String?): Painter? {
    return null
}
