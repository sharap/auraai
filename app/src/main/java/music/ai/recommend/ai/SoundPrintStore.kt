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
 * The prints of this device's files: what each one's sound hashes to, and what the file looked
 * like when it was hashed.
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
    }

    /** songId to stamp and print; null until the table has been read. */
    @Volatile
    private var cache: ConcurrentHashMap<Long, Pair<String, String>>? = null

    private val mutex = Mutex()

    private suspend fun loaded(): ConcurrentHashMap<Long, Pair<String, String>> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: withContext(Dispatchers.IO) {
                val map = ConcurrentHashMap<Long, Pair<String, String>>()
                runCatching { dao.getAll() }.getOrDefault(emptyList()).forEach { row ->
                    val stamp = row.stamp
                    val print = row.print
                    if (stamp != null && print != null) map[row.songId] = stamp to print
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
    suspend fun all(): Map<Long, String> = loaded().mapValues { (_, held) -> held.second }

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
        if (held != null && held.first == stamp) return held.second
        val print = withContext(Dispatchers.IO) { SoundPrint.of(file) } ?: return null
        remember(listOf(SoundPrintEntity(songId, stamp, print)))
        return print
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
        val held = loaded()
        val wanted = SoundPrint.missing(
            tracks = tracks,
            held = held.mapValues { (_, row) -> row.first },
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
            taken.add(SoundPrintEntity(songId, stamp, print))
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

    private suspend fun remember(prints: List<SoundPrintEntity>) = withContext(Dispatchers.IO) {
        try {
            dao.insertAll(prints)
            val held = loaded()
            for (row in prints) {
                val stamp = row.stamp
                val print = row.print
                if (stamp != null && print != null) held[row.songId] = stamp to print
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not store ${prints.size} prints", e)
        }
    }
}
