package music.ai.recommend.db

import com.google.gson.annotations.SerializedName

data class EmbeddingEntity(
    @SerializedName("path")
    val path: String,
    @SerializedName("vector")
    val vector: List<Float>,
    /**
     * When this vector was offered to the other devices, or 0 for one that has not been.
     *
     * In the same row as the vector and gone when it goes: a change of analysis version throws
     * every vector away, and the question of whether the old ones were published goes with them.
     * Set for vectors that could not travel at all — a format with no shared decoder — as well as
     * for ones that did, since the answer for those will not change while the analysis stays put.
     */
    @SerializedName("publishedAt")
    val publishedAt: Long = 0L,
    /**
     * Set only on a row that is not a vector but a refusal: the analysis tried to read the file
     * and could not, and this is what the file looked like at the time — see
     * [music.ai.recommend.ai.AnalysisBacklog.stamp]. Null on every real vector, whose [vector] is
     * the 512 floats; a refusal's is empty, because there is nothing to put there.
     *
     * In this table rather than one of its own because it answers the same question a vector
     * answers — has the analysis dealt with this track? — and because it has to be thrown away
     * with the vectors when the analysis version changes: a file the old decoders could not read
     * is not a file the new ones cannot.
     */
    @SerializedName("refusedStamp")
    val refusedStamp: String? = null
)

/**
 * Bumped whenever anything that changes the value of an embedding changes — the feature extractor,
 * the excerpt selection, the projection. See the Android app's `EmbeddingStore`, where the same
 * number lives: a vector carries it between devices and is refused if it differs, so the two must
 * agree. Here rather than beside the desktop's storage because sync, which is common code, has to
 * be able to say it.
 *
 * Version 2 corrected two things at once: the log-mel features now match `ClapFeatureExtractor`
 * (the previous ones used an HTK mel scale from 0 Hz, unnormalised filters on whole-bin edges and a
 * natural logarithm, which put the 64 mel channels on the wrong frequencies entirely), and the
 * excerpt is taken from the middle of the track rather than from a fixed 20 s in.
 *
 * Version 3 pinned the excerpt to an exact range of samples, see `Excerpt`. Before it, the window
 * was centred on a duration the tag reader gives in whole seconds, while the Android app centred it
 * on microseconds, so the same track was embedded from ten seconds that began up to half a second
 * apart — worth a cosine of about 0.95 where 0.9985 is the bar for two embeddings describing the
 * same thing. That is also what made them impossible to carry between devices.
 */
const val ANALYSIS_VERSION = 3

/** How many numbers one embedding has. CLAP's audio tower projects to 512. */
const val EMBEDDING_DIMENSIONS = 512
