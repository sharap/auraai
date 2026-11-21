package music.ai.recommend.platform

expect class ClapTextEncoder() {
    suspend fun encode(text: String): FloatArray?

    /** Closes the inference session. The text model is ~126 MB of resident memory. */
    fun release()
}
