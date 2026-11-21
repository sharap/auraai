package music.ai.recommend.model

import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val uri: String,
    val path: String,
    val folderName: String,
    val year: String = "",
    val size: Long = 0L
)
