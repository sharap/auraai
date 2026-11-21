package music.ai.recommend.platform

import music.ai.recommend.model.Song

actual class DailyMixBuilder actual constructor() {
    actual suspend fun playlist(
        library: List<Song>,
        favourites: Set<String>,
        rebuild: Boolean
    ): DailyPlaylist = DailyPlaylist("", emptyList())

    actual fun millisUntilNextDay(): Long = Long.MAX_VALUE

    actual fun today(): String = ""

    actual suspend fun offered(): OfferedMix? = null

    actual suspend fun adopt(day: String, attempt: Int, from: String, paths: List<String>) = Unit
}
