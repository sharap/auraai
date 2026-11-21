package music.ai.recommend.platform

import music.ai.recommend.model.Folder

expect class MusicScanner() {
    fun scanMusic(): List<Folder>
    fun scanCustomPath(path: String): List<Folder>
}
