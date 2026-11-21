package music.ai.recommend.sync

/**
 * The embeddings a device holds, as [SyncCoordinator] needs to see them.
 *
 * The same idea as [HistoryStore]: everything about how a platform keeps its vectors — a Room table
 * of BLOBs keyed by MediaStore id here, a packed file keyed by path there — stops at this interface,
 * so the part that decides what travels and on what terms is one piece of code rather than two.
 *
 * Two of these questions can only be answered by the platform, and both are load-bearing.
 *
 * [decoderFor] because what turned a file into samples is the thing that decides whether a vector
 * may be believed at all, and only the platform knows the file.
 *
 * [excerptStartMs] because it has to be the window the analysis actually used, and each platform's
 * analyser reads a duration from its own place: the container on the phone, the tag reader on the
 * desktop. Working it back from the key's duration would be close and not exact — measured over 400
 * mp3s the two readings on one phone differ by at most a millisecond, which crosses one of
 * `Excerpt.startMs`'s two-second buckets about once in two thousand tracks. Once in two thousand is
 * a couple of tracks in this library quietly holding a vector made from a different ten seconds,
 * which is exactly the error this whole design refuses to make.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface VectorStore<Track> {

    /** One of this device's embeddings. */
    data class Held<Track>(val track: Track, val vector: FloatArray) {
        // Written out because a data class compares a FloatArray by identity.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Held<*>) return false
            return track == other.track && vector.contentEquals(other.vector)
        }

        override fun hashCode(): Int = 31 * (track?.hashCode() ?: 0) + vector.contentHashCode()
    }

    /**
     * The terms this device analyses on, or null when it cannot say yet — no model chosen, nothing
     * analysed. A device that cannot say its own terms neither sends nor takes in.
     */
    suspend fun terms(): VectorTerms?

    /** What this device would use to turn [track]'s file into samples. */
    suspend fun decoderFor(track: Track): String

    /**
     * Where this device's analysis would start the ten seconds for [track], or null when the file
     * cannot be read — which is also the honest answer for a file that has gone.
     */
    suspend fun excerptStartMs(track: Track): Long?

    /**
     * Vectors this device holds and has not dealt with yet, at most [limit] of them.
     *
     * Order does not matter, but a track returned here must not be returned again until
     * [markHandled] has been told about it, or a pass would offer the same vectors forever.
     */
    suspend fun toPublish(limit: Int): List<Held<Track>>

    /**
     * Records that these tracks have been dealt with and need not be offered again.
     *
     * It includes the ones that could not travel — a format with no shared decoder — because the
     * answer for those will not change while the analysis version stays the same, and reconsidering
     * them every pass is the cost of a full library scan for nothing. When the analysis version
     * does change every vector is thrown away anyway, and this goes with them.
     */
    suspend fun markHandled(tracks: List<Track>)

    /** Which tracks already have a vector, so an offer of one is not work for nothing. */
    suspend fun analysed(): Set<Track>

    /**
     * The vectors this device holds for exactly these tracks, in no particular order.
     *
     * For a peer that asked by name instead of fetching a whole file: [toPublish] answers "what
     * has not gone out yet", which is a different question and the wrong one once somebody has
     * said precisely what they are missing. Tracks with no vector here are left out, and nothing
     * is marked by asking — being served to a peer is not the same as having been published.
     */
    suspend fun held(tracks: List<Track>): List<Held<Track>>

    /**
     * Stores vectors that came from elsewhere, as if this device had computed them.
     *
     * @return how many were stored. Zero for a failure, and the coordinator then leaves its
     *   progress alone so the batch is met again.
     */
    suspend fun takeIn(vectors: List<Held<Track>>): Int
}
