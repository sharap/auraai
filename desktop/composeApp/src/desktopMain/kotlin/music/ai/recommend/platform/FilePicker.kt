package music.ai.recommend.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import java.io.FileInputStream
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import org.jaudiotagger.audio.AudioFileIO
import java.io.ByteArrayInputStream
import java.io.File

actual fun pickDirectory(): String? {
    return showChooser(JFileChooser.DIRECTORIES_ONLY, "Select Music Folder", null)
}

actual fun pickImageFile(): String? {
    return showChooser(
        JFileChooser.FILES_ONLY, 
        "Select Background Image", 
        FileNameExtensionFilter("Images", "jpg", "png", "jpeg", "webp")
    )
}

@Composable
actual fun rememberLocalImagePainter(path: String?): Painter? {
    if (path == null) return null
    return remember(path) {
        try {
            val file = File(path)
            if (file.exists()) {
                BitmapPainter(loadImageBitmap(FileInputStream(file)))
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
actual fun rememberArtworkPainter(songPath: String?): Painter? {
    if (songPath == null) return null
    return remember(songPath) {
        try {
            val songFile = File(songPath)
            
            // 1. Try embedded artwork
            val audioFile = AudioFileIO.read(songFile)
            val artwork = audioFile.tag?.firstArtwork
            if (artwork != null) {
                val bytes = artwork.binaryData
                return@remember BitmapPainter(loadImageBitmap(ByteArrayInputStream(bytes)))
            }
            
            // 2. Look for image files in the same folder
            val parentDir = songFile.parentFile
            if (parentDir != null && parentDir.isDirectory) {
                val commonNames = listOf("cover", "folder", "album", "front")
                val extensions = listOf("jpg", "jpeg", "png", "webp")
                
                // Try common names first
                for (name in commonNames) {
                    for (ext in extensions) {
                        val imgFile = File(parentDir, "$name.$ext")
                        if (imgFile.exists()) {
                            return@remember BitmapPainter(loadImageBitmap(FileInputStream(imgFile)))
                        }
                    }
                }
                
                // Try any image file in the folder as fallback
                val anyImg = parentDir.listFiles { _, name ->
                    val lowName = name.lowercase()
                    lowName.endsWith(".jpg") || lowName.endsWith(".png") || lowName.endsWith(".jpeg")
                }?.firstOrNull()
                
                if (anyImg != null) {
                    return@remember BitmapPainter(loadImageBitmap(FileInputStream(anyImg)))
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
}

private fun showChooser(mode: Int, title: String, filter: FileNameExtensionFilter?): String? {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (e: Exception) {}

    val chooser = JFileChooser()
    chooser.fileSelectionMode = mode
    chooser.dialogTitle = title
    filter?.let { chooser.fileFilter = it }
    
    val result = chooser.showOpenDialog(null)
    
    return if (result == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}
