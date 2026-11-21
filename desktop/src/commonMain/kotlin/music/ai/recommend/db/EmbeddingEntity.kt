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


