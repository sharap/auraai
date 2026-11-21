package music.ai.recommend.sync

import music.ai.recommend.ai.AnalysisBacklog
import music.ai.recommend.ai.Excerpt
import music.ai.recommend.ai.SharedDecoder
import music.ai.recommend.db.ANALYSIS_VERSION
import music.ai.recommend.db.EMBEDDING_DIMENSIONS
import music.ai.recommend.db.EmbeddingEntity
import music.ai.recommend.db.MusicDao
import music.ai.recommend.model.Song

/**
 * This app's embeddings, as [SyncCoordinator] wants to see them.
 *
 * All of what is particular to this port lives here: the vectors are a packed file keyed by path,
 * the excerpt start is remembered beside the sound print, and a duration comes from the tag reader.
 * That is the whole asymmetry between the two implementations, and it is why the question is asked
 * of the platform at all.
 *
 * Not a copy. The Android app's answer to the same question is `EmbeddingVectorStore`, over a Room
 * table keyed by MediaStore id and a container duration read through MediaExtractor.
 */
class DaoVectorStore(
    private val dao: MusicDao,
    private val songs: suspend () -> List<Song>,
    /** sha256 of the audio model this app analyses with. */
    private val model: String
) : VectorStore<String> {

    private var durations: Map<String, Long> = emptyMap()
    private var durationsFrom = 0

    private suspend fun durationOf(path: String): Long? {
        val library = songs()
        if (library.size != durationsFrom) {
            durations = library.associate { it.path to it.duration }
            durationsFrom = library.size
        }
        return durations[path]?.takeIf { it > 0 }
    }

    override suspend fun terms(): VectorTerms = VectorTerms(
        model = model,
        analysisVersion = ANALYSIS_VERSION,
        dimensions = EMBEDDING_DIMENSIONS
    )

    override suspend fun decoderFor(track: String): String =
        SharedDecoder.of(track) ?: PLATFORM

    /**
     * Which millisecond the analysed excerpt starts at, worked out once per file and then
     * remembered beside its print.
     *
     * Asked once per track per conversation, by both devices and in both directions: the one
     * handing a vector over names the window it describes, the one taking a vector in names its
     * own window to judge whether they agree. The number that arrives in a record is no help to
     * the receiver, since comparing the other device's answer with itself is no comparison.
     *
     * Working it out walks the file, and asked afresh every time that is the entire cost of an
     * exchange: one measured conversation of 4223 vectors spent six minutes in here, saying
     * nothing, while the phone at the other end gave up waiting after two seconds and set about
     * computing what was already on its way.
     */
    override suspend fun excerptStartMs(track: String): Long? {
        val file = java.io.File(track)
        if (!file.isFile) {
            // No file to stamp, so nothing worth remembering — but the answer is not therefore
            // unknown. Here, unlike on the phone, a duration comes from the library and not from
            // the file, so a track on an unplugged drive still has a window that can be named,
            // and for a format with no shared decoder that duration is the only answer there is.
            return durationOf(track)?.let { Excerpt.startMs(it) }
        }
        val stamp = AnalysisBacklog.stamp(file.length(), file.lastModified())
        val held = dao.soundPrint(track)
        if (held != null && held.stamp == stamp) held.excerptStartMs?.let { return it }
        val start = excerptStartFromFile(file) ?: return null
        dao.rememberSoundPrint(
            track,
            MusicDao.FilePrint(
                stamp = stamp,
                // Carried over rather than dropped: a row holds everything known about its file,
                // and writing one that forgot the print would mean hashing it again.
                print = held?.takeIf { it.stamp == stamp }?.print,
                excerptStartMs = start
            )
        )
        return start
    }

    /** The walk itself, which is what the remembering exists to do once. */
    private suspend fun excerptStartFromFile(file: java.io.File): Long? {
        // For a format the shared decoder handles, the decoder's own count of the samples — which
        // is what it chose the window by. The tag reader's whole seconds are a different number,
        // and naming the window with them is how a correct vector comes to be refused: measured
        // against one phone, about a quarter of a library came out one two-second bucket apart.
        SharedDecoder.startMs(file)?.let { return it }
        val duration = durationOf(file.path) ?: return null
        return Excerpt.startMs(duration)
    }

    override suspend fun toPublish(limit: Int): List<VectorStore.Held<String>> =
        dao.vectorsToPublish(limit).map { row ->
            VectorStore.Held(row.path, FloatArray(row.vector.size) { row.vector[it] })
        }

    override suspend fun markHandled(tracks: List<String>) {
        dao.markVectorsPublished(tracks, System.currentTimeMillis())
    }

    override suspend fun analysed(): Set<String> =
        dao.getAllEmbeddings().mapTo(HashSet()) { it.path }

    override suspend fun held(tracks: List<String>): List<VectorStore.Held<String>> {
        if (tracks.isEmpty()) return emptyList()
        // The whole table is already a map in memory, so this is a few lookups rather than a query.
        val wanted = tracks.toHashSet()
        return dao.getAllEmbeddings()
            .filter { it.path in wanted && it.vector.isNotEmpty() }
            .map { row -> VectorStore.Held(row.path, FloatArray(row.vector.size) { row.vector[it] }) }
    }

    override suspend fun takeIn(vectors: List<VectorStore.Held<String>>): Int =
        dao.insertEmbeddings(vectors.map { held ->
            EmbeddingEntity(
                path = held.track,
                vector = held.vector.toList(),
                // Left unoffered on purpose, so that this machine will put them in the folder too.
                // The folder then holds every vector once per device that has it, which is what
                // makes it a cache anything can be restored from: a device that loses its own gets
                // them back from whoever else was handed them, instead of analysing a library
                // again. The price is each vector being carried twice with two devices.
                publishedAt = 0L
            )
        })

    private companion object {
        /** What this platform decodes everything but mp3 with. Never allowed to travel. */
        const val PLATFORM = "ffmpeg-desktop"
    }
}
