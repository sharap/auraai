package music.ai.recommend.sync

/**
 * Which files in a device's directory are ours to read.
 *
 * The folder is somebody else's workspace. Syncthing writes a partly transferred file as
 * `.syncthing.<name>.tmp` and renames it when it is whole, a backup tool leaves its own litter, and
 * a reader that takes whatever the directory lists will read all of it. Found on a live exchange,
 * where a phone had read twelve lines out of a temporary file and kept a note of its progress
 * through a name that no longer existed.
 *
 * Nothing was lost by it — every one of those records was in the real file too, and a record stored
 * twice is turned away by the store — but a file being transferred is the one place where the
 * format's promise does not hold. A file this program appends to can only ever be half-written at
 * the end; one Syncthing is filling block by block can be half-written in the middle, and a line
 * that fails to decode in the middle is passed over rather than waited for.
 *
 * So a file is read only if it is shaped like one of the writers made it.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object DayFile {

    /**
     * `history/2026-11-05.jsonl`, or `vectors/2026-11-05/0003.jsonl` where a provider could not
     * append and the day became a directory.
     */
    private val SHAPE = Regex("""[a-z]+/\d{4}-\d{2}-\d{2}(\.jsonl|/\d{1,6}\.jsonl)""")

    fun isOurs(relativePath: String): Boolean = SHAPE.matches(relativePath)
}
