package music.ai.recommend.model


data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    /**
     * Where the platform can open the track, as text.
     *
     * A string and not a platform type, which is what let this class be shared at all. The Android
     * app held an `android.net.Uri` here and so could not share the one model everything else is
     * written against — and with it, nothing that takes a song could be shared either. The cost of
     * the change turned out to be two lines: the scanner calls `toString()` on what MediaStore
     * gives it, and the one place that needs a real `Uri` parses it back. Media3 takes the string
     * as it is.
     */
    val uri: String,
    val path: String,
    val folderName: String,
    val year: String = "",
    val size: Long = 0L
)
