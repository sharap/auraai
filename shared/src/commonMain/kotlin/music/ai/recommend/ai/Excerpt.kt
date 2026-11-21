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

    /** At most how many windows one analysis listens to; see [startsMs]. */
    const val WINDOWS = 3

    /**
     * Where each of the windows one analysis listens to starts.
     *
     * Three of them, averaged, rather than the one in the middle. Ten seconds is a noisy sample of
     * a track: two different windows of the same track agree to a cosine of only about 0.80, so a
     * single window describes whichever moment it happened to land on. Averaging three leaves a
     * vector that barely depends on where the cut was made — 0.87 to 0.96 under a one-step shift of
     * every window — and one that sits much closer to the track as a whole: 0.91 to 0.97 against
     * the mean of nineteen windows.
     *
     * What that buys, measured rather than assumed. On a labelled slice of the real library (122
     * tracks, six genres, paired per track): the class a track is assigned by comparing it with a
     * description — which is how smart albums get their names — goes from 87 right to 97, eleven
     * fixed against one broken; the nearest neighbour of the same class from 117 to 121; the
     * separation between classes from 2.18 to 2.40 sigma. On the whole library, judged by the
     * albums the production clustering actually makes, the effect is real but modest, and it took
     * twenty paired subsets to say so honestly — one clustering of one library says as much about
     * which run it was as about the strategy (see WindowStrategyExperimentTest): purity against the
     * genre tag 0.787 to 0.807, albums 38 to 44, covered tracks 1047 to 1169, three windows ahead
     * in sixteen of twenty subsets on purity and on the album count.
     *
     * **The spread is deliberate and was measured too.** It works out at roughly a fifth, the
     * middle and four fifths of the track. Windows at the very edges (5% and 95%)
     * are worse than the single middle one — intros, fades and silence drag the average nowhere.
     * Narrowing to 30/50/70 gives most of it up. Nineteen windows are no better than three on the
     * numbers that matter, and worse at matching a description. Three at roughly a quarter, the
     * middle and three quarters is the knee of that curve.
     *
     * **Why the outer two are derived from the middle** rather than from the duration directly:
     * everything above [startMs] about the two platforms disagreeing over how long a track is
     * applies to every window. The middle is quantised so that it only depends on which
     * two-second bucket the duration falls into, which is what makes a travelling vector
     * trustworthy; computing the outer windows from the duration again would let them differ where
     * the middle agrees, and a vector built from different audio would be accepted as
     * interchangeable. Derived from the middle, agreement on the middle *is* agreement on all
     * three — so [excerptStartMs][startMs] stays the whole identity of the window set, together
     * with [ANALYSIS_VERSION], which says what the set is.
     *
     * The outer two are not rounded to whole seconds, and do not need to be: they are derived
     * arithmetic on a number both devices already agree about, not a fresh reading of the file.
     *
     * @return one or three starts in ascending order. One for anything shorter than three
     *   excerpts: under thirty seconds there is no room for three windows that hear different
     *   music, and three overlapping copies of the same ten seconds would cost three times the
     *   work for one vector's worth of information.
     */
    fun startsMs(durationMs: Long): LongArray = startsAroundMs(startMs(durationMs))

    /**
     * The same windows, named by the middle one instead of by the track's length.
     *
     * This is the entry point a decoder uses. The sync record names a vector by the middle window
     * as the *decoder* counted it — `SharedDecoder.startMs`, from its own walk of the samples,
     * because the platform's idea of the duration is a different number and naming the window with
     * it is how a correct vector comes to be refused. The windows therefore have to be derived
     * from that same number, or the record would name one window set and the vector be made from
     * another, and the two devices would accept each other's vectors while listening to different
     * audio.
     *
     * No clamp is needed at the end: with the middle at `d / 2 - 5` seconds, the last window
     * starts at `1.6 * middle`, which is at most `d - 10` for every track that reaches three
     * windows at all.
     */
    fun startsAroundMs(middleMs: Long): LongArray {
        // Under ten seconds of middle means under thirty seconds of track — the middle is
        // `duration / 2 - 5` — and three windows into that is three copies of the same ten
        // seconds: three times the work for one vector's worth of information.
        if (middleMs < LENGTH_MS) return longArrayOf(middleMs.coerceAtLeast(0L))

        // Three fifths of the way to the middle, which puts the outer windows at about a fifth and
        // four fifths of the track — the spread that measured best on the labelled slice. Half,
        // the obvious choice, is a narrow 24/48/72, and windows at the very edges are worse than
        // the single middle one: intros, fades and silence drag the average nowhere.
        val spread = middleMs * 3 / 5
        return longArrayOf(middleMs - spread, middleMs, middleMs + spread)
    }

    /**
     * The one vector that stands for a track, from the vectors of its windows.
     *
     * The mean, back to unit length. Here rather than in either scanner because both ports have to
     * produce the same number for the same track — that is the whole point of the sync — and
     * "average three unit vectors" is exactly the kind of one-line arithmetic that drifts when it
     * exists twice.
     */
    fun averaged(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty()) { "no window vectors to average" }
        val size = vectors.first().size
        val sum = FloatArray(size)
        for (vector in vectors) {
            require(vector.size == size) { "window vectors of different sizes: $size and ${vector.size}" }
            for (i in 0 until size) sum[i] += vector[i]
        }
        var norm = 0f
        for (x in sum) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) for (i in sum.indices) sum[i] /= norm
        return sum
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
