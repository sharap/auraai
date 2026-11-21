package music.ai.recommend.ai

import java.io.File

/**
 * Which files both apps can turn into samples with the same code, and how.
 *
 * One place to ask, because the question comes up in four — each app's scanner, to decode, and
 * each app's vector store, to say what decoded a file and which ten seconds it would have taken.
 * Four chains of `if` is how one of them comes to be missing a format, and a vector store that
 * disagrees with its own scanner is the worst kind of wrong here: it would offer a vector under
 * the name of a decoder that did not make it.
 *
 * What is not here is the platform. A format nobody shares still gets analysed — MediaCodec on the
 * phone, ffmpeg on the desktop — and its embedding is as good as any for this device's own use. It
 * simply cannot travel, and [music.ai.recommend.sync.VectorTerms.TRAVELS] is where that is said.
 * With mp3, flac, wav and m4a shared, what is left is ogg, opus and the odd wma.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object SharedDecoder {

    /** Every decoder named here, for the terms a vector travels on. */
    val NAMES: Set<String> = setOf(
        Mp3Excerpt.DECODER,
        FlacExcerpt.DECODER,
        WavExcerpt.DECODER,
        M4aExcerpt.DECODER
    )

    /** @return the decoder both apps would use for this file, or null when only the platform can. */
    fun of(path: String): String? = when {
        Mp3Excerpt.handles(path) -> Mp3Excerpt.DECODER
        FlacExcerpt.handles(path) -> FlacExcerpt.DECODER
        WavExcerpt.handles(path) -> WavExcerpt.DECODER
        M4aExcerpt.handles(path) -> M4aExcerpt.DECODER
        else -> null
    }

    fun handles(path: String): Boolean = of(path) != null

    /**
     * Where the excerpt begins for this file, by the decoder's own reading of its length.
     *
     * Null for a file no shared decoder handles — and also for one that will not read, which is
     * the same answer as far as the caller is concerned: there is nothing to name an excerpt by,
     * so the platform's reading of the duration is used and the vector stays at home.
     */
    fun startMs(file: File): Long? = when {
        Mp3Excerpt.handles(file.path) -> Mp3Excerpt.startMs(file)
        FlacExcerpt.handles(file.path) -> FlacExcerpt.startMs(file)
        WavExcerpt.handles(file.path) -> WavExcerpt.startMs(file)
        M4aExcerpt.handles(file.path) -> M4aExcerpt.startMs(file)
        else -> null
    }

    /**
     * @return the excerpt as the model wants it, or an empty array when no shared decoder handles
     *   this file or the file will not decode. An empty answer means "ask the platform", and the
     *   caller then records the platform as what made the vector.
     */
    fun decode(file: File): FloatArray = when {
        Mp3Excerpt.handles(file.path) -> Mp3Excerpt.decode(file)
        FlacExcerpt.handles(file.path) -> FlacExcerpt.decode(file)
        WavExcerpt.handles(file.path) -> WavExcerpt.decode(file)
        M4aExcerpt.handles(file.path) -> M4aExcerpt.decode(file)
        else -> FloatArray(0)
    }
}
