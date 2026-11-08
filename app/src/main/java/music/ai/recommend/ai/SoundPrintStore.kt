package music.ai.recommend.ai

import android.content.Context
import android.util.Log
import music.ai.recommend.db.AppDatabase
import music.ai.recommend.db.SoundPrintDao
import music.ai.recommend.db.SoundPrintEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What this device knows about its files from their bytes: what each one's sound hashes to, which
 * millisecond its analysed excerpt starts at, and what the file looked like when both were worked
 * out.
 *
 * A read-through cache over a small table, like [EmbeddingStore] and for the same reason — the
 * prints are asked for whenever a track has to be named to another device, which during a sync is
 * once a message.
 *
 * Nothing here decides *when* a print is worth taking; see [refresh] and [of]. What it does decide
 * is that a print belongs to a file and not to a track: the stamp is the file's length and
 * modification time, exactly as [AnalysisBacklog.stamp] writes it, and a file that has changed has
 * no print until somebody takes a new one. The same rule as a refusal's, deliberately — two
 * notions of "this file has changed" would be two things to get wrong.
 */
class SoundPrintStore private constructor(private val dao: SoundPrintDao) {

    companion object {
        @Volatile
        private var instance: SoundPrintStore? = null

        /**
         * One instance per process, for the same reason as [EmbeddingStore]: the scan takes prints
         * while sync reads them, and two caches would mean one of them is always wrong.
         */
        fun getInstance(context: Context): SoundPrintStore =
            instance ?: synchronized(this) {
                instance ?: SoundPrintStore(
                    AppDatabase.getDatabase(context.applicationContext).soundPrintDao()
                ).also { instance = it }
            }

        private const val TAG = "SoundPrintStore"

        /** How many worked-out rows are worth one transaction; see [flush]. */
        private const val WRITE_AT = 64
    }

    /**
     * What is known about one file, under the stamp it was known at.
     *
     * Either half may be missing: a track hashed by an older build has no excerpt start, and a
     * track whose excerpt was named on the way into a conversation may never have been hashed,
     * because naming an excerpt is asked of every track a peer mentions while hashing is asked of
     * the ones about to be analysed.
     */
    private class Held(val stamp: String, val print: String?, val excerptStartMs: Long?)

    /** songId to what is known about its file; null until the table has been read. */
    @Volatile
    private var cache: ConcurrentHashMap<Long, Held>? = null

    private val mutex = Mutex()

    /**
     * Rows worked out but not yet written.
     *
     * Because the excerpt start is asked for one track at a time — a conversation asks per track,
     * in both directions — while writing is worth doing in batches. Four thousand single-row
     * transactions is the kind of cost that replaces the one being removed here. Nothing in this
     * buffer is ever lost in a way that matters: a row that never reaches the table is worked out
     * again next time, which is exactly the state the whole library was in before this existed.
     */
    private val unwritten = ConcurrentHashMap<Long, SoundPrintEntity>()

    private suspend fun loaded(): ConcurrentHashMap<Long, Held> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: withContext(Dispatchers.IO) {
                val map = ConcurrentHashMap<Long, Held>()
                runCatching { dao.getAll() }.getOrDefault(emptyList()).forEach { row ->
                    val stamp = row.stamp
                    if (stamp != null) map[row.songId] = Held(stamp, row.print, row.excerptStartMs)
                }
                map
            }.also { cache = it }
        }
    }

    /**
     * Every print this device holds, by track.
     *
     * Handed over without touching the file system: this is read on the way to naming a track, and
     * confirming four and a half thousand stamps would be four and a half thousand questions to
     * the file system for an answer that [refresh] has already worked out.
     */
    suspend fun all(): Map<Long, String> =
        loaded().mapNotNull { (songId, held) -> held.print?.let { songId to it } }.toMap()

    /**
     * The print of one file, taken now if there is none or the file has changed.
     *
     * For the moment before an analysis: a print costs a seek and a quarter of a megabyte, and
     * knowing it can save the analysis entirely when another device has already done the work.
     */
    suspend fun of(songId: Long, path: String?): String? {
        val file = path?.let { File(it) }?.takeIf { it.isFile } ?: return null
        val stamp = AnalysisBacklog.stamp(file.length(), file.lastModified())
        val held = loaded()[songId]
        if (held != null && held.stamp == stamp) held.print?.let { return it }
        val print = withContext(Dispatchers.IO) { SoundPrint.of(file) } ?: return null
        val startKnown = held?.takeIf { it.stamp == stamp }?.excerptStartMs
        remember(listOf(SoundPrintEntity(songId, stamp, print, startKnown)))
        return print
    }

    /**
     * Which millisecond of this file the analysed excerpt starts at, worked out once and then
     * remembered.
     *
     * The number is asked for once per track per conversation, by both devices and in both
     * directions — the one handing a vector over names the window it describes, the one taking a
     * vector in names its own window to judge whether they agree. Working it out walks the file,
     * so asked afresh every time it is the whole cost of an exchange: one measured conversation of
     * 4223 vectors spent six silent minutes in it, while the phone at the other end gave up
     * waiting after two seconds and set about computing what was already on its way.
     *
     * Remembered under the file's stamp, so a file that is replaced is measured again and a file
     * that is not never is. [compute] is where the platform's own answer comes from; it is only
     * ever called for a file this store has no current answer for.
     */
    suspend fun excerptStartOf(songId: Long, path: String?, compute: suspend (File) -> Long?): Long? {
        val file = path?.let { File(it) }?.takeIf { it.isFile } ?: return null
        val stamp = AnalysisBacklog.stamp(file.length(), file.lastModified())
        val held = loaded()[songId]
        if (held != null && held.stamp == stamp) held.excerptStartMs?.let { return it }
        val start = compute(file) ?: return null
        val printKnown = held?.takeIf { it.stamp == stamp }?.print
        keep(SoundPrintEntity(songId, stamp, printKnown, start))
        return start
    }

    /**
     * Holds one row in memory and writes the buffer once it is worth a transaction.
     *
     * The cache is right immediately either way, so a second question about the same file is
     * answered from memory whether or not the table has heard about it yet.
     */
    private suspend fun keep(row: SoundPrintEntity) {
        val stamp = row.stamp ?: return
        loaded()[row.songId] = Held(stamp, row.print, row.excerptStartMs)
        unwritten[row.songId] = row
        if (unwritten.size >= WRITE_AT) flush()
    }

    /** Writes whatever [keep] is still holding. */
    suspend fun flush() {
        if (unwritten.isEmpty()) return
        val rows = unwritten.values.toList()
        unwritten.keys.removeAll(rows.mapTo(HashSet()) { it.songId })
        remember(rows)
    }

    /**
     * Takes the prints that are missing or stale, at most [limit] of them.
     *
     * Stamps are confirmed with the file system here, which is a question per track and the reason
     * this is a pass of its own rather than something [all] does. It is cheap: a stat is
     * microseconds, and a print is 0.19 ms on a desktop and a couple of milliseconds on a phone,
     * so a library of four and a half thousand is seconds rather than the minutes that hashing
     * whole files or decoding excerpts would have cost.
     *
     * @return how many prints were taken.
     */
    suspend fun refresh(tracks: Map<Long, String>, limit: Int = Int.MAX_VALUE): Int {
        if (tracks.isEmpty() || limit <= 0) return 0
        // Before taking prints, so that rows this pass replaces are not overwritten by a buffer
        // written afterwards with an older idea of them.
        flush()
        val held = loaded()
        val wanted = SoundPrint.missing(
            tracks = tracks,
            // Only rows that actually carry a print count as having one. A row may hold a stamp
            // and an excerpt start and no print at all — naming an excerpt is asked of every
            // track a peer mentions, hashing only of the ones about to be analysed — and reading
            // such a row as "already hashed" would be how a track quietly never gets a print.
            held = held.mapNotNull { (songId, row) -> row.print?.let { songId to row.stamp } }.toMap(),
            stampOf = ::stampOf
        )
        val taken = ArrayList<SoundPrintEntity>()
        for (songId in wanted) {
            if (taken.size >= limit) break
            val path = tracks[songId] ?: continue
            val file = File(path)
            val stamp = stampOf(path)
            if (stamp.isEmpty()) continue
            val print = withContext(Dispatchers.IO) { SoundPrint.of(file) } ?: continue
            val startKnown = held[songId]?.takeIf { it.stamp == stamp }?.excerptStartMs
            taken.add(SoundPrintEntity(songId, stamp, print, startKnown))
        }
        if (taken.isEmpty()) return 0
        remember(taken)
        return taken.size
    }

    /** Forgets the prints of tracks this library no longer holds. */
    suspend fun keepOnly(songIds: Collection<Long>) = withContext(Dispatchers.IO) {
        runCatching { dao.keepOnly(songIds.toList()) }
            .onFailure { Log.e(TAG, "Could not tidy the prints", it) }
        val held = cache ?: return@withContext
        held.keys.retainAll(songIds.toSet())
    }

    /** What the file looks like now, which is the only reason a print is ever taken again. */
    private fun stampOf(path: String): String {
        val file = File(path)
        if (!file.isFile) return ""
        return AnalysisBacklog.stamp(file.length(), file.lastModified())
    }

    /**
     * Writes rows and keeps the cache agreeing with them.
     *
     * A row carries everything known about its file, so whoever calls this has to carry over what
     * it is not itself changing — which is why both callers look up what is already held under the
     * same stamp. Writing a row that dropped the other half would make the next conversation walk
     * the file again for a number that was already known.
     */
    private suspend fun remember(prints: List<SoundPrintEntity>) = withContext(Dispatchers.IO) {
        try {
            dao.insertAll(prints)
            val held = loaded()
            for (row in prints) {
                val stamp = row.stamp ?: continue
                held[row.songId] = Held(stamp, row.print, row.excerptStartMs)
            }
            // A row written here replaces whatever the buffer was still holding for the same
            // track. Without this, a print taken by [of] would be written and then undone by a
            // later flush of a buffered row that predates it and knows of no print at all.
            unwritten.keys.removeAll(prints.mapTo(HashSet()) { it.songId })
        } catch (e: Exception) {
            Log.e(TAG, "Could not store ${prints.size} prints", e)
        }
    }
}
