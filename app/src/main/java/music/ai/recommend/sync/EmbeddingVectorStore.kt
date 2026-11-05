package music.ai.recommend.sync

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import music.ai.recommend.ai.EmbeddingStore
import music.ai.recommend.ai.Excerpt
import music.ai.recommend.ai.Mp3Excerpt
import music.ai.recommend.ai.ModelRepository
import music.ai.recommend.model.Song

/**
 * This app's embeddings, as [SyncCoordinator] wants to see them.
 *
 * All of what is particular to Android lives here: the vectors are a Room table of BLOBs keyed by
 * MediaStore id, the model is whichever export the library was analysed with, and a duration comes
 * from the container through MediaExtractor — which is the reading the analysis itself uses, and
 * therefore the only one that may be used to name an excerpt.
 */
class EmbeddingVectorStore(
    private val embeddings: EmbeddingStore,
    private val models: ModelRepository,
    private val songs: suspend () -> List<Song>
) : VectorStore<Long> {

    /** Paths by song id, refreshed when the library has changed under us. */
    private var paths: Map<Long, String> = emptyMap()
    private var pathsFrom = 0

    private suspend fun pathFor(songId: Long): String? {
        val library = songs()
        if (library.size != pathsFrom) {
            paths = library.associate { it.id to it.path }
            pathsFrom = library.size
        }
        return paths[songId]
    }

    override suspend fun terms(): VectorTerms? {
        // The variant that produced the vectors already here, so an incoming one joins a library
        // in the same space. Null only before anything has been analysed, when what this device
        // would use is the honest answer instead.
        val variant = models.embeddingsVariant.value ?: models.audioVariant.value
        val model = variant.asset.sha256 ?: return null
        return VectorTerms(
            model = model,
            analysisVersion = EmbeddingStore.ANALYSIS_VERSION,
            dimensions = EmbeddingStore.DIMENSIONS
        )
    }

    override suspend fun decoderFor(track: Long): String {
        val path = pathFor(track) ?: return UNKNOWN
        return if (Mp3Excerpt.handles(path)) Mp3Excerpt.DECODER else PLATFORM
    }

    override suspend fun excerptStartMs(track: Long): Long? {
        val path = pathFor(track) ?: return null
        val duration = containerDurationMs(path) ?: return null
        return Excerpt.startMs(duration)
    }

    override suspend fun toPublish(limit: Int): List<VectorStore.Held<Long>> =
        embeddings.notPublished(limit).map { (songId, vector) -> VectorStore.Held(songId, vector) }

    override suspend fun markHandled(tracks: List<Long>) {
        embeddings.markPublished(tracks, System.currentTimeMillis())
    }

    override suspend fun analysed(): Set<Long> = embeddings.ids()

    override suspend fun takeIn(vectors: List<VectorStore.Held<Long>>): Int {
        val stored = embeddings.putAll(vectors.associate { it.track to it.vector })
        if (stored > 0) {
            // These need not be offered back out, and marking them now also means a vector taken
            // in is not mistaken later for one this device computed and never published.
            embeddings.markPublished(vectors.map { it.track }, System.currentTimeMillis())
        }
        return stored
    }

    /**
     * The duration as the container states it, in milliseconds — the same reading `AiScanner` uses
     * to place the excerpt, and the reason this is asked of the platform at all.
     *
     * Opening the file costs a few milliseconds, and it happens for the tracks of one pass rather
     * than for the library, so a backfill pays it a few hundred at a time.
     */
    private fun containerDurationMs(path: String): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                if (!format.containsKey(MediaFormat.KEY_DURATION)) return null
                return format.getLong(MediaFormat.KEY_DURATION) / 1000
            }
            null
        } catch (e: Exception) {
            // Gone, unreadable, or not audio at all. All three mean there is nothing to name.
            Log.i(TAG, "Could not read the duration of $path: ${e.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TAG = "EmbeddingVectorStore"

        /** What this platform decodes everything but mp3 with. Never allowed to travel. */
        const val PLATFORM = "mediacodec-android"

        /** A track whose file this device cannot find; nothing may be believed about it. */
        const val UNKNOWN = "unknown"
    }
}
