package music.ai.recommend.sync

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import music.ai.recommend.ai.ANALYSIS_VERSION
import music.ai.recommend.ai.EMBEDDING_DIMENSIONS
import music.ai.recommend.ai.EmbeddingStore
import music.ai.recommend.ai.Excerpt
import music.ai.recommend.ai.SharedDecoder
import music.ai.recommend.ai.ModelRepository
import music.ai.recommend.ai.SoundPrintStore
import music.ai.recommend.model.Song
import java.io.File

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
    /**
     * Where what is known about a file from its bytes is kept, which here is the excerpt start:
     * working it out costs a walk over the file, and a conversation asks for it once per track in
     * either direction. Null in a test that does not care, and then every answer is worked out
     * afresh — correct, and slow in exactly the way this exists to stop.
     */
    private val prints: SoundPrintStore? = null,
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
            analysisVersion = ANALYSIS_VERSION,
            dimensions = EMBEDDING_DIMENSIONS
        )
    }

    override suspend fun decoderFor(track: Long): String {
        val path = pathFor(track) ?: return UNKNOWN
        return SharedDecoder.of(path) ?: PLATFORM
    }

    override suspend fun excerptStartMs(track: Long): Long? {
        val path = pathFor(track) ?: return null
        val prints = prints ?: return excerptStartFromFile(File(path))
        return prints.excerptStartOf(track, path) { file -> excerptStartFromFile(file) }
    }

    /** The walk itself, which is what [SoundPrintStore.excerptStartOf] exists to do once. */
    private fun excerptStartFromFile(file: File): Long? {
        // For a format the shared decoder handles, the decoder's own count of the samples — which
        // is what it chose the window by. The platform's duration is a different number and naming
        // the window with it is how a correct vector comes to be refused.
        SharedDecoder.startMs(file)?.let { return it }
        val duration = containerDurationMs(file.path) ?: return null
        return Excerpt.startMs(duration)
    }

    override suspend fun toPublish(limit: Int): List<VectorStore.Held<Long>> =
        embeddings.notPublished(limit).map { (songId, vector) -> VectorStore.Held(songId, vector) }

    override suspend fun markHandled(tracks: List<Long>) {
        embeddings.markPublished(tracks, System.currentTimeMillis())
    }

    override suspend fun analysed(): Set<Long> = embeddings.ids()

    override suspend fun held(tracks: List<Long>): List<VectorStore.Held<Long>> {
        if (tracks.isEmpty()) return emptyList()
        // The whole table is already a map in memory, so this is a few lookups rather than a query.
        val all = embeddings.all()
        return tracks.mapNotNull { id -> all[id]?.let { VectorStore.Held(id, it) } }
    }

    override suspend fun takeIn(vectors: List<VectorStore.Held<Long>>): Int =
        // Left unoffered on purpose, so that this device will put them in the folder too. The
        // folder then holds every vector once per device that has it, which is what makes it a
        // cache anything can be restored from: a device that loses its own gets them back from
        // whoever else was handed them, instead of analysing a library again. The price is the
        // folder carrying each vector twice with two devices — a few megabytes for hours of work.
        embeddings.putAll(vectors.associate { it.track to it.vector })

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
