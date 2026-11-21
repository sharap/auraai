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
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
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

    /**
     * Resamples mono audio from [sourceRate] to [SAMPLE_RATE], cut to exactly one excerpt.
     *
     * Linear interpolation, and deliberately so. Against ffmpeg's polyphase filter it moves an
     * embedding by a cosine of 0.002, while two different high-quality resamplers agree to 1.0000 —
     * so the quality on offer is not the point. What is the point is that both devices run this
     * function rather than two different good ones, because the model turns small differences in
     * the signal into larger ones in the embedding: on one wav a difference of 0.0008 in the
     * waveform came out as 0.025 in the vector.
     *
     * Returns fewer than [SAMPLES] only when the input genuinely holds less audio than that.
     */
    fun resample(data: FloatArray, sourceRate: Int): FloatArray {
        if (data.isEmpty()) return data
        if (sourceRate == SAMPLE_RATE || sourceRate <= 0) {
            return if (data.size <= SAMPLES) data else data.copyOf(SAMPLES)
        }
        val ratio = sourceRate.toDouble() / SAMPLE_RATE
        // The last output sample interpolates towards the sample after it, so the input has to
        // reach one position past where that output sample lands.
        val fits = ((data.size - 1) / ratio).toInt().coerceAtLeast(0)
        val size = if (fits < SAMPLES) fits else SAMPLES
        val resampled = FloatArray(size)
        for (i in 0 until size) {
            val position = i * ratio
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val next = if (index + 1 < data.size) index + 1 else data.size - 1
            resampled[i] = data[index] * (1 - fraction) + data[next] * fraction
        }
        return resampled
    }
}
