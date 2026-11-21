package music.ai.recommend.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

expect fun pickDirectory(): String?
expect fun pickImageFile(): String?

@Composable
expect fun rememberLocalImagePainter(path: String?): Painter?

@Composable
expect fun rememberArtworkPainter(songPath: String?): Painter?
