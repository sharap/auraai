package music.ai.recommend.sync

import com.google.gson.JsonParser
import java.io.File

/**
 * This device's copy of every playlist change it knows of, its own and the ones that arrived.
 *
 * The log is the truth and the playlists are the view of it, so the log has to be kept rather than
 * the state: an operation that arrives late belongs in the middle of the order, and only the log
 * can put it there. Keeping the state alone would mean having an answer for "a change from before
 * the last one you applied" and there isn't one.
 *
 * One operation a line, appended, read once into memory and kept there. A log of ten thousand
 * changes is two megabytes of text and a list of ten thousand small objects — a few years of
 * somebody organising their music. When that stops being true it will want compacting, which the
 * format allows for: everything before a point can be replaced by the state at that point. Not
 * yet, and deliberately not yet: compaction that nobody needs is a second way for the two devices
 * to disagree.
 *
 * Written through append and never rewritten, so an interrupted write costs the last line. A line
 * that will not parse is skipped, and the rest of the log stands.
 *
 * Every method holds the log's own lock, and [appendOwn] exists because of it: taking a counter and
 * writing with it are two steps, and between them a sync pass can write. Two changes with one
 * counter from one device are the same change as far as the log is concerned, so the second would
 * be dropped as already known — a heart tapped twice in a second, and the second tap lost.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class PlaylistLog(private val file: File) {

    private var ops: MutableList<PlaylistOp>? = null

    /** Every change, in no particular order; [PlaylistReplay] puts them in one. */
    fun all(): List<PlaylistOp> = synchronized(this) { loaded().toList() }

    /** The counter this device's next change should carry. */
    fun nextSeq(): Long = synchronized(this) { PlaylistReplay.nextSeq(loaded()) }

    /**
     * Records changes of this device's own, numbering them as it writes them.
     *
     * [make] is handed the counter the first of them should carry and must number any further ones
     * upwards from it. It is called with the log's lock held, so nothing can take the same counter
     * in between.
     */
    fun appendOwn(make: (firstSeq: Long) -> List<PlaylistOp>): Int? = synchronized(this) {
        appendLocked(make(PlaylistReplay.nextSeq(loaded())))
    }

    /**
     * Adds changes to the log.
     *
     * @return how many were added, or **null** when they could not be stored. The difference
     *   matters to the caller: nothing added because they were all already here means the pass
     *   succeeded, while nothing added because the disk refused means the folder must be left
     *   looking unread, or those changes are marked read and never arrive again.
     *
     *   A change already here — the same device and the same counter — is not added twice: a
     *   folder read again offers the same operations, and they are the same ones rather than new.
     */
    fun append(fresh: List<PlaylistOp>): Int? = synchronized(this) { appendLocked(fresh) }

    private fun appendLocked(fresh: List<PlaylistOp>): Int? {
        if (fresh.isEmpty()) return 0
        val held = loaded()
        val known = held.mapTo(HashSet()) { it.deviceId to it.seq }
        val adding = fresh.filter { (it.deviceId to it.seq) !in known }
        if (adding.isEmpty()) return 0
        return try {
            file.parentFile?.mkdirs()
            file.appendText(adding.joinToString(separator = "\n", postfix = "\n") { it.toJson().toString() })
            held.addAll(adding)
            adding.size
        } catch (e: Exception) {
            // Nothing is added to what is held either: the next pass meets the same changes, and
            // a log that says less than the disk is safer than one that says more.
            null
        }
    }

    /** This device's own changes past [afterSeq], oldest first, for the outbox. */
    fun own(deviceId: String, afterSeq: Long, limit: Int): List<PlaylistOp> = synchronized(this) {
        loaded().asSequence()
            .filter { it.deviceId == deviceId && it.seq > afterSeq }
            .sortedBy { it.seq }
            .take(limit)
            .toList()
    }

    private fun loaded(): MutableList<PlaylistOp> {
        ops?.let { return it }
        val read = ArrayList<PlaylistOp>()
        try {
            if (file.isFile) {
                for (line in file.readLines()) {
                    if (line.isBlank()) continue
                    val json = runCatching { JsonParser.parseString(line) }.getOrNull() ?: continue
                    val obj = json.asJsonObjectOrNull() ?: continue
                    PlaylistOp.fromJson(obj)?.let { read.add(it) }
                }
            }
        } catch (e: Exception) {
            // A log that cannot be read is treated as empty rather than as a reason to stop. The
            // folder still has every change in it, so the next pass reads them back.
            read.clear()
        }
        return read.also { ops = it }
    }

    private fun com.google.gson.JsonElement.asJsonObjectOrNull() =
        if (isJsonObject) asJsonObject else null
}
