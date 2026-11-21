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
     * The windows to analyse this file by.
     *
     * By the decoder's own reading of the length where there is one, because that is the number the
     * sync record names the vector with; by the platform's [durationMs] otherwise, which is also
     * the case where the vector stays at home.
     */
    fun windowsMs(file: File, durationMs: Long): LongArray =
        startMs(file)?.let { Excerpt.startsAroundMs(it) } ?: Excerpt.startsMs(durationMs)

    /**
     * @return the excerpt as the model wants it, or an empty array when no shared decoder handles
     *   this file or the file will not decode. An empty answer means "ask the platform", and the
     *   caller then records the platform as what made the vector.
     */
    /**
     * One window of [file], decoded to exactly one excerpt's worth of mono 48 kHz samples.
     *
     * @param startMs where the window begins. Null means the one window [Excerpt.startMs] picks,
     *   which is what a caller that wants a single excerpt asks for; the scanners pass each of
     *   [Excerpt.startsMs] in turn and average what comes back.
     */
    fun decode(file: File, startMs: Long? = null): FloatArray = when {
        Mp3Excerpt.handles(file.path) -> Mp3Excerpt.decode(file, startMs)
        FlacExcerpt.handles(file.path) -> FlacExcerpt.decode(file, startMs)
        WavExcerpt.handles(file.path) -> WavExcerpt.decode(file, startMs)
        M4aExcerpt.handles(file.path) -> M4aExcerpt.decode(file, startMs)
        else -> FloatArray(0)
    }
}
