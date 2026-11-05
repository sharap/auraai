package music.ai.recommend.ai

/**
 * Which ten seconds of a track the analysis listens to, and how many samples that is.
 *
 * This is a contract between platforms, not a detail of this app. An embedding is worth carrying
 * from one device to another only if both devices embedded the same samples, and the model turns
 * out to be unforgiving about that: measured on one mp3, moving the window by five milliseconds
 * costs a cosine of 0.012, and anything past twenty milliseconds settles at 0.94-0.96 — far below
 * the 0.9985 at which this app calls two embeddings interchangeable. There is no tolerance to spend,
 * so the rule has to produce the same answer on both sides rather than nearly the same.
 *
 * The hard part is that the two platforms do not agree on how long a track is. Android reports
 * microseconds from the container, while the desktop's tag reader reports whole seconds, so the
 * middle of the track differs by up to half a second between them — enough to cost 0.95. Hence the
 * truncation to whole seconds followed by integer halving: the answer only depends on which
 * two-second bucket the duration falls into, which the two sides do agree on except when their
 * estimates straddle a boundary. Those cases are not papered over: a synced embedding travels with
 * the [startMs] it was made from, and a device that computes a different one re-analyses the track
 * itself instead of trusting the vector.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object Excerpt {

    /** How much audio the model gets. The reference extractor is built around ten seconds. */
    const val LENGTH_MS = 10_000L

    /** What the model expects to be fed, and what the features are computed at. */
    const val SAMPLE_RATE = 48_000

    /** Exactly how many samples one excerpt is. Anything less takes the repeat-pad branch. */
    const val SAMPLES = (LENGTH_MS * SAMPLE_RATE / 1000).toInt()

    /**
     * Where the excerpt starts, in milliseconds from the beginning of the track.
     *
     * The middle, so that the analysis hears the track rather than its intro, quantised as
     * described above. Zero for anything that is not longer than the excerpt itself, which is then
     * repeat-padded the way the reference extractor does it.
     *
     * @param durationMs the track's length. Zero or negative when it could not be read, in which
     *   case the excerpt starts at the beginning — the only honest answer without a length.
     */
    fun startMs(durationMs: Long): Long {
        if (durationMs <= LENGTH_MS) return 0L
        val wholeSeconds = durationMs / 1000
        val startSeconds = wholeSeconds / 2 - LENGTH_MS / 2000
        return if (startSeconds <= 0) 0L else startSeconds * 1000
    }

    /**
     * How many samples at [sourceRate] are needed to produce [SAMPLES] after resampling.
     *
     * One more than the arithmetic requires, because the resampler interpolates between a sample
     * and the one after it and the last output sample must not have to invent its neighbour.
     */
    fun sourceSamplesNeeded(sourceRate: Int): Int {
        if (sourceRate <= 0 || sourceRate == SAMPLE_RATE) return SAMPLES
        val exact = SAMPLES.toLong() * sourceRate / SAMPLE_RATE
        return (exact + 2).toInt()
    }
}
