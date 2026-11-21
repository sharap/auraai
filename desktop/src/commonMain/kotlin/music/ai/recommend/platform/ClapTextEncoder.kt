package music.ai.recommend.platform

expect class ClapTextEncoder() {
    suspend fun encode(text: String): FloatArray?

    /**
     * The text model is not on this machine, so no encoding was attempted.
     *
     * Told apart from an encoding that simply failed because the two want different words: one is
     * "download it first", the other is "something is wrong".
     */
    fun needsDownload(): Boolean

    /** Closes the inference session. The text model is ~126 MB of resident memory. */
    fun release()
}
