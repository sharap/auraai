package music.ai.recommend.platform

expect class ClapTextEncoder() {
    suspend fun encode(text: String): FloatArray?

    /**
     * The model this [text] would go through is not on this machine, so no encoding was attempted.
     *
     * Told apart from an encoding that simply failed because the two want different words: one is
     * "download it first", the other is "something is wrong".
     *
     * Takes the text because there are two doors and they are fetched separately: a query with any
     * Cyrillic in it goes through the Russian student, which is 32 MB that someone who never
     * searches in Russian should not be made to download.
     */
    fun needsDownload(text: String = ""): Boolean

    /** Closes the inference session. The text model is ~126 MB of resident memory. */
    fun release()
}
