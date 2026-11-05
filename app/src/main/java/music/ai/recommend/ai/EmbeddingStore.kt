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

    @Volatile
    private var migrated = false

    /** Every stored embedding, keyed by song id. The returned map must not be mutated. */
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
     * Ids of analysed songs. Reads only the id column when the vectors are not already cached,
     * which is what the UI badges and the scan counter actually need.
     */
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

    suspend fun put(songId: Long, vector: FloatArray) = withContext(Dispatchers.IO) {
        dao.insert(EmbeddingEntity(songId, vector.toBlob()))
        cache?.put(songId, vector)
        Unit
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

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.deleteAll()
            cache = null
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
        for (row in rows) {
            val blob = row.vector ?: continue
            result[row.songId] = blob.toFloatArray()
        }
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
