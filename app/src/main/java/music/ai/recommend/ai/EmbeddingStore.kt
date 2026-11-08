package music.ai.recommend.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import music.ai.recommend.db.AppDatabase
import music.ai.recommend.db.EmbeddingEntity
import music.ai.recommend.db.LegacyEmbeddings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-through cache over the embeddings table.
 *
 * Similarity work — AI search, AI shuffle, "play similar" — needs the whole set of vectors at once
 * and used to re-read and re-parse the table on every invocation. Here the table is read once and
 * kept in memory as primitive float arrays; writes update the cache in place.
 */
class EmbeddingStore private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: EmbeddingStore? = null

        /**
         * One instance per process. The scan service writes embeddings while search and AI shuffle
         * read them; separate instances would each hold a cache and the readers would go stale.
         */
        fun getInstance(context: Context): EmbeddingStore =
            instance ?: synchronized(this) {
                instance ?: EmbeddingStore(context.applicationContext).also { instance = it }
            }

        private const val TAG = "EmbeddingStore"
        private const val PREFS = "embedding_store"
        private const val KEY_ANALYSIS_VERSION = "analysis_version"

        /**
         * Bumped whenever anything that changes the value of an embedding changes — the feature
         * extractor, the excerpt selection, the projection. Version 2 corrected the log-mel features
         * to match ClapFeatureExtractor; measured against text queries the peak similarity of a
         * matching description tripled, so version 1 embeddings are not worth keeping.
         *
         * Version 3 pinned the excerpt down to an exact range of samples, see [Excerpt]. Until then
         * the window began wherever the seek landed and so came out short of ten seconds, which the
         * feature extractor padded with silence; on the tracks measured that moved an embedding by
         * a cosine of up to 0.14. Version 2 embeddings therefore describe a slightly different ten
         * seconds of each track than version 3 does, and mixing the two would leave the similarity
         * space half a step out of true.
         */
        internal const val ANALYSIS_VERSION = 3

        /** How many numbers one of these has. CLAP's audio tower projects to 512. */
        internal const val DIMENSIONS = 512
    }


    private val database = AppDatabase.getDatabase(context.applicationContext)
    private val dao = database.embeddingDao()
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /**
     * True when embeddings from an older analysis had to be thrown away. The settings screen says
     * so, because otherwise the library would appear to have un-analysed itself.
     */
    @Volatile
    var analysisWasReset: Boolean = false
        private set

    // Concurrent because a running scan writes through put() while search or AI shuffle reads.
    @Volatile
    private var cache: ConcurrentHashMap<Long, FloatArray>? = null

    /**
     * Tracks that could not be read, with the stamp of the file each was refused for.
     *
     * Filled together with [cache], since loading it reads every row anyway. Null means not yet
     * read, which is not the same as empty — a scan must not mistake "we have not looked" for
     * "nothing was refused" and re-decode five broken files on every pass.
     */
    @Volatile
    private var refused: ConcurrentHashMap<Long, String>? = null

    @Volatile
    private var migrated = false

    /**
     * Every stored embedding, keyed by song id. The returned map must not be mutated.
     *
     * Refusals are not in it, and that is the whole point of their being apart: everything that
     * computes a similarity reads this map, and a zero vector in it would be a track at distance
     * 1 from the entire library — which DBSCAN would have to treat as a point ([SmartAlbumClustering]
     * says as much in its contract), and which the wave would score 0 on every term and so rank
     * above anything the listener is moving away from. A file that cannot be read must be absent
     * from the similarity space, not sitting at its origin.
     */
    suspend fun all(): Map<Long, FloatArray> = withContext(Dispatchers.IO) {
        cache ?: mutex.withLock {
            cache ?: run {
                migrateLegacyRows()
                discardOutdatedAnalysis()
                load()
            }.also { cache = it }
        }
    }

    /**
     * Ids of analysed songs — tracks that hold a vector, which is what the UI badges, the scan
     * counter and sync all mean by the word. Reads only the id column when the vectors are not
     * already cached. Refused tracks are not analysed; see [refusals].
     */
    /**
     * Whether this track has a vector right now.
     *
     * One lookup rather than a copy of every id, because the scan asks it per track: a vector that
     * arrived from the other device while the scan was walking the library saves a decode and an
     * inference, and asking [ids] for that would copy four and a half thousand of them each time.
     */
    suspend fun has(songId: Long): Boolean = withContext(Dispatchers.IO) {
        cache?.containsKey(songId) ?: ids().contains(songId)
    }

    /**
     * How many tracks the analysis has already dealt with — vectors and refusals alike — or null
     * while the table has not been read yet.
     *
     * Without suspending, unlike everything else here, because of where it is asked from: the
     * beacon announces what this device is missing from a socket thread every few seconds, and a
     * question that could block or touch the database there would be the wrong question in the
     * wrong place. Null rather than zero for "not known yet", since zero is what a device with an
     * empty table truthfully has.
     */
    fun dealtWithNow(): Int? {
        val held = cache ?: return null
        return held.size + (refused?.size ?: 0)
    }

    suspend fun ids(): Set<Long> = withContext(Dispatchers.IO) {
        cache?.keys?.toSet() ?: mutex.withLock {
            cache?.keys?.toSet() ?: run {
                // Without this a freshly upgraded install would report zero analysed songs and
                // rescan the whole library, because the vectors are still in the legacy table.
                migrateLegacyRows()
                discardOutdatedAnalysis()
                try {
                    dao.getAllIds().toSet()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read embedding ids", e)
                    emptySet()
                }
            }
        }
    }

    /** Stores a vector, and with it the news that the track is no longer refused. */
    suspend fun put(songId: Long, vector: FloatArray) = withContext(Dispatchers.IO) {
        // A row carrying a vector has no stamp, and the insert replaces the whole row, so a
        // refusal recorded earlier goes with it.
        dao.insert(EmbeddingEntity(songId, vector.toBlob()))
        cache?.put(songId, vector)
        refused?.remove(songId)
        Unit
    }

    /**
     * Records that this file could not be read, so the scan stops offering it on every pass.
     *
     * @param stamp what the file looked like, from [AnalysisBacklog.stamp]. It is what lets the
     *   refusal expire: a file of a different length, or written since, is a different file and
     *   goes back into the backlog.
     */
    suspend fun refuse(songId: Long, stamp: String) = withContext(Dispatchers.IO) {
        try {
            dao.insert(EmbeddingEntity(songId, null).also { it.refusedStamp = stamp })
            cache?.remove(songId)
            refused?.put(songId, stamp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record that song $songId could not be read", e)
        }
        Unit
    }

    /**
     * Tracks that were tried and could not be read, each with the stamp of the file refused.
     *
     * @see AnalysisBacklog.pending
     */
    suspend fun refusals(): Map<Long, String> = withContext(Dispatchers.IO) {
        refused?.let { return@withContext it.toMap() }
        try {
            dao.getRefusals().associate { it.songId to (it.stamp ?: "") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read which files could not be read", e)
            emptyMap()
        }
    }

    /**
     * Several at once, which is how vectors arrive from another device: a few hundred in one pass,
     * and a transaction's worth of inserts rather than a few hundred separate ones.
     *
     * @return how many were stored, so a caller can tell a failure from an empty batch.
     */
    suspend fun putAll(vectors: Map<Long, FloatArray>): Int = withContext(Dispatchers.IO) {
        if (vectors.isEmpty()) return@withContext 0
        try {
            dao.insertAll(vectors.map { (songId, vector) -> EmbeddingEntity(songId, vector.toBlob()) })
            cache?.putAll(vectors)
            // A vector from a device that could read the file settles what this one could not.
            refused?.let { held -> vectors.keys.forEach { held.remove(it) } }
            vectors.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store ${vectors.size} embeddings from elsewhere", e)
            0
        }
    }

    /**
     * Drops these vectors, cache and table together.
     *
     * The cache is the reason this exists as a method rather than as a query somebody runs: it is
     * read by search, by the wave and by the scan, and a row deleted behind its back stays
     * analysed as far as all three are concerned.
     */
    suspend fun remove(songIds: List<Long>) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext
        try {
            dao.delete(songIds)
            cache?.let { held -> songIds.forEach { held.remove(it) } }
            refused?.let { held -> songIds.forEach { held.remove(it) } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to drop ${songIds.size} embeddings", e)
        }
    }

    /** Vectors not yet offered to the other devices; see [EmbeddingEntity.publishedAt]. */
    suspend fun notPublished(limit: Int): Map<Long, FloatArray> = withContext(Dispatchers.IO) {
        try {
            dao.notPublished(limit).associate { it.songId to it.vector.toFloatArray() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read what is left to publish", e)
            emptyMap()
        }
    }

    /** Records that these have been dealt with, whether or not they could travel. */
    suspend fun markPublished(songIds: List<Long>, at: Long) = withContext(Dispatchers.IO) {
        try {
            if (songIds.isNotEmpty()) dao.markPublished(songIds, at)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark ${songIds.size} embeddings as published", e)
        }
    }

    /**
     * Offers every vector to the other devices again; see [music.ai.recommend.db.EmbeddingDao.offerEverythingAgain].
     *
     * @return how many will now go out, or zero if the table could not be written.
     */
    suspend fun offerEverythingAgain(): Int = withContext(Dispatchers.IO) {
        try {
            dao.offerEverythingAgain()
            dao.countNotPublished()
        } catch (e: Exception) {
            Log.e(TAG, "Could not offer the vectors again", e)
            0
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.deleteAll()
            cache = null
            refused = null
            analysisWasReset = false
            prefs.edit().putInt(KEY_ANALYSIS_VERSION, ANALYSIS_VERSION).apply()
        }
    }

    /**
     * Drops embeddings produced by an older analysis. Must be called with [mutex] held.
     *
     * They are not merely stale but wrong: mixing them with current ones would give a similarity
     * space where half the tracks sit in the wrong place.
     */
    private fun discardOutdatedAnalysis() {
        try {
            val stored = prefs.getInt(KEY_ANALYSIS_VERSION, 1)
            if (stored == ANALYSIS_VERSION) return
            val count = runCatching { dao.getCount() }.getOrDefault(0)
            if (count > 0) {
                dao.deleteAll()
                analysisWasReset = true
                Log.w(TAG, "Discarded $count embeddings from analysis version $stored")
            }
            prefs.edit().putInt(KEY_ANALYSIS_VERSION, ANALYSIS_VERSION).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not check the analysis version", e)
        }
    }

    private fun load(): ConcurrentHashMap<Long, FloatArray> {
        val rows = dao.getAll()
        val result = ConcurrentHashMap<Long, FloatArray>(rows.size * 2)
        val stamps = ConcurrentHashMap<Long, String>()
        for (row in rows) {
            val stamp = row.refusedStamp
            if (stamp != null) {
                stamps[row.songId] = stamp
                continue
            }
            val blob = row.vector ?: continue
            result[row.songId] = blob.toFloatArray()
        }
        // Set here rather than by the caller: the rows have just been read, and reading them again
        // for the refusals would be the whole table twice.
        refused = stamps
        return result
    }

    /**
     * One-time conversion of v1 JSON vectors into BLOBs. No-op once the legacy table is gone.
     * Must be called with [mutex] held.
     */
    private fun migrateLegacyRows() {
        if (migrated) return
        migrated = true
        try {
            if (!LegacyEmbeddings.exists(database)) return
            val legacy = LegacyEmbeddings.readAll(database)
            if (legacy.isNotEmpty()) {
                val gson = Gson()
                val listType = object : TypeToken<List<Float>>() {}.type
                val converted = legacy.mapNotNull { row ->
                    val json = row.vector ?: return@mapNotNull null
                    val floats: List<Float>? = runCatching { gson.fromJson<List<Float>>(json, listType) }.getOrNull()
                    if (floats.isNullOrEmpty()) null
                    else EmbeddingEntity(row.songId, FloatArray(floats.size) { floats[it] }.toBlob())
                }
                if (converted.isNotEmpty()) dao.insertAll(converted)
                Log.d(TAG, "Migrated ${converted.size}/${legacy.size} embeddings to BLOB storage")
            }
            LegacyEmbeddings.drop(database)
        } catch (e: Exception) {
            // A failed migration costs a rescan, not a crash.
            Log.e(TAG, "Legacy embedding migration failed", e)
            runCatching { LegacyEmbeddings.drop(database) }
        }
    }
}

internal fun FloatArray.toBlob(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    buffer.asFloatBuffer().put(this)
    return buffer.array()
}

internal fun ByteArray.toFloatArray(): FloatArray {
    val floats = FloatArray(size / Float.SIZE_BYTES)
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
    return floats
}
