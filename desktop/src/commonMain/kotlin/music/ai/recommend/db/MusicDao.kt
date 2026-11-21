package music.ai.recommend.db

import music.ai.recommend.model.AppSettings
import music.ai.recommend.model.Playlist

interface MusicDao {
    /**
     * Every stored vector. Refusals are not among them: everything that computes a similarity
     * reads this, and a track with no direction would sit at distance 1 from the whole library —
     * a point DBSCAN has to cluster and a candidate the wave scores 0 on every term, which beats
     * anything the listener is moving away from. See [refusals].
     */
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    /**
     * Tracks the analysis tried and could not read, each with the stamp of the file it gave up on;
     * see [music.ai.recommend.ai.AnalysisBacklog].
     */
    suspend fun refusals(): Map<String, String>

    /**
     * What this device knows about one file from its bytes, under the stamp it was learnt at.
     *
     * Either half may be missing. A file hashed by an older build has no excerpt start; a file
     * whose excerpt was named on the way into a conversation may have no print, because naming an
     * excerpt is asked of every track a peer mentions while hashing is asked only of the ones
     * about to be analysed.
     */
    data class FilePrint(
        /** "length:modifiedAt" when this was learnt; a different one means a different file. */
        val stamp: String,
        /** sha256 of the sound, lowercase hex, or null if this file has not been hashed. */
        val print: String? = null,
        /**
         * Which millisecond the analysed excerpt starts at, or null if nobody has worked it out.
         *
         * Null and not zero, because zero is a real answer: a recording shorter than the excerpt
         * is analysed from its very beginning.
         */
        val excerptStartMs: Long? = null
    )

    /**
     * What each file's sound hashes to and where its excerpt starts, by path.
     *
     * Kept apart from the vectors because the tracks it is most needed for are the ones with no
     * vector yet — a device names what it is missing to its peer, and naming it by sound rather
     * than by spelling is the whole point — and because it has to outlive an analysis version
     * change, which throws every vector away. The sound of a file does not depend on which model
     * read it. See `SoundPrint`.
     */
    suspend fun soundPrints(): Map<String, FilePrint>

    /**
     * What is known about one file, without copying the whole table.
     *
     * One lookup rather than a map of four and a half thousand, because this is asked per track:
     * a conversation needs the excerpt start of every track it mentions, in either direction.
     */
    suspend fun soundPrint(path: String): FilePrint?

    /** Stores prints, replacing whatever was held for those paths. */
    suspend fun rememberSoundPrints(prints: Map<String, FilePrint>)

    /**
     * Holds one row and writes the file only once it is worth rewriting.
     *
     * The table is a file rewritten whole, so a row per write would be four thousand rewrites
     * during one conversation — the very cost this exists to remove. A row that never reaches the
     * file is worked out again next time, which is where the whole library was before.
     */
    suspend fun rememberSoundPrint(path: String, held: FilePrint)

    /**
     * How many tracks the analysis has already dealt with — vectors and refusals alike — or null
     * while the table has not been read yet.
     *
     * Without suspending, unlike everything else here, because of where it is asked from: the
     * beacon announces what this device is missing from a socket thread every few seconds, and a
     * question that could block there would be the wrong question in the wrong place. Null rather
     * than zero for "not known yet", since zero is what an empty table truthfully has.
     */
    fun dealtWithNow(): Int?

    /**
     * Whether this track has a vector right now.
     *
     * One lookup rather than a copy of every row, because the scan asks it per track: a vector
     * that arrived from the other device while the scan was walking the library saves a decode and
     * an inference.
     */
    suspend fun hasEmbedding(path: String): Boolean

    suspend fun insertEmbedding(embedding: EmbeddingEntity)

    /** Remembers that this file could not be read, so the next scan does not try it again for nothing. */
    suspend fun refuseEmbedding(path: String, stamp: String)

    suspend fun deleteEmbedding(path: String)

    /** Several at once, for the vectors whose track the library no longer has. */
    suspend fun deleteEmbeddings(paths: List<String>)

    suspend fun clearAllEmbeddings()

    /**
     * Several at once, which is how vectors arrive from another device, and saved before this
     * returns so that a crash does not lose what was just taken in.
     *
     * @return how many were stored. Zero for a failure, so the caller can leave the folder looking
     *   unread rather than marking a batch taken in that was not.
     */
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>): Int

    /**
     * Vectors not yet offered to the other devices; see [EmbeddingEntity.publishedAt].
     *
     * Refusals are left out, and not as an optimisation. "I could not read this file" is a verdict
     * about one machine's decoders; the device at the other end may read the format perfectly
     * well, and if it took the refusal for a vector it would mark the track analysed and never
     * look at it again.
     */
    suspend fun vectorsToPublish(limit: Int): List<EmbeddingEntity>

    /** Records that these have been dealt with, whether or not they could travel. */
    suspend fun markVectorsPublished(paths: List<String>, at: Long)

    /**
     * Offers every vector to the other devices again.
     *
     * Run when this machine's records start going somewhere they have never been — a folder just
     * chosen, or the socket's own directory the first time it is used. The marks say "already
     * offered", which was true of the *previous* destination and says nothing about this one, and a
     * destination that never receives them can never hand them back.
     *
     * @return how many will now go out.
     */
    suspend fun offerEveryVectorAgain(): Int

    /**
     * True when embeddings from an older analysis had to be thrown away on startup.
     *
     * The settings screen says so, because otherwise the library would appear to have
     * un-analysed itself between one launch and the next.
     */
    suspend fun analysisWasReset(): Boolean

    /** Clears the notice, once a fresh scan has replaced what was discarded. */
    suspend fun acknowledgeAnalysisReset()

    /**
     * The listening history, which the playlist of the day is built from.
     *
     * Appended to rather than rewritten: a listen is recorded every few minutes, and rewriting the
     * whole log each time would be pure waste. Everything stays on this machine.
     */
    suspend fun appendPlayEvent(event: PlayEventEntity)

    /** Every recorded listen, oldest first. Pruning keeps this bounded. */
    suspend fun playEvents(): List<PlayEventEntity>

    /** Drops listens recorded before [cutoff]; they no longer carry weight in the taste profile. */
    suspend fun prunePlayEvents(cutoff: Long)

    /**
     * Listens heard on another device, already matched to tracks in this library, written in one
     * go because that is how they arrive.
     *
     * Kept apart from [appendPlayEvent] by [PlayEventEntity.deviceId], which is what stops them
     * being sent back out as this machine's own.
     *
     * @return how many were stored. Zero means none were, and the caller must then not treat the
     *   batch as taken in — it will be offered again.
     */
    suspend fun appendPlayEvents(events: List<PlayEventEntity>): Int

    /**
     * This machine's own listens past [afterId], oldest first, for the sync outbox. At most [limit]
     * of them, so a history of years does not have to be described in one pass.
     */
    suspend fun playEventsToExport(afterId: Long, limit: Int): List<PlayEventEntity>

    /** The newest id in the log, so an outbox starting for the first time starts from the present. */
    suspend fun newestPlayEventId(): Long

    /**
     * Listens taken in from other devices since [since] — what the merge is told this machine
     * already holds, so that reading the same file twice costs nothing.
     */
    suspend fun playEventsFromOtherDevices(since: Long): List<PlayEventEntity>
    
    suspend fun saveSettings(settings: AppSettings)
    suspend fun loadSettings(): AppSettings?
    
    suspend fun savePlaylists(playlists: List<Playlist>)
    suspend fun loadPlaylists(allSongs: List<music.ai.recommend.model.Song>): List<Playlist>
    
    suspend fun saveFavorites(songPaths: Set<String>)
    suspend fun loadFavorites(): Set<String>
}
