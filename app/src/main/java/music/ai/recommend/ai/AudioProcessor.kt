package music.ai.recommend.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Log-mel features in the form CLAP was trained on.
 *
 * Mirrors `ClapFeatureExtractor` with this checkpoint's `preprocessor_config.json`
 * (`truncation: rand_trunc`, so the Slaney filter bank), which means: 50 Hz to 14 kHz over 64
 * Slaney-scaled, area-normalised triangular filters on fractional FFT-bin boundaries, a periodic
 * Hann window, reflect-padded centred frames, and a decibel magnitude scale.
 *
 * The previous version differed on every one of those points — an HTK mel scale from 0 Hz, no
 * filter normalisation, filter edges truncated to whole bins, and a natural logarithm. Against the
 * reference its output correlated only 0.76 and its dynamic range was compressed by a factor of
 * 4.39, i.e. `10 / ln(10)`.
 */
class AudioProcessor {
    companion object {
        private const val SAMPLE_RATE = 48000
        private const val N_FFT = 1024
        private const val HOP_LENGTH = 480
        private const val N_MELS = 64
        private const val F_MIN = 50.0
        private const val F_MAX = 14000.0
        private const val SPECTRUM_BINS = N_FFT / 2 + 1

        /** `nb_max_samples`: ten seconds at 48 kHz. */
        const val NUM_SAMPLES = 480_000

        /** Centring adds half a window at each end, giving 1 + (481024 - 1024) / 480 frames. */
        private const val PADDED_SAMPLES = NUM_SAMPLES + N_FFT
        private const val PAD = N_FFT / 2
        private const val NUM_FRAMES = 1001

        /** Floor from `power_to_db`, i.e. -100 dB. */
        private const val MIN_POWER = 1e-10f

        /** Length of the array [extractFeatures] returns. */
        const val FEATURE_SIZE = NUM_FRAMES * N_MELS

        private fun hertzToMel(hz: Double): Double {
            val minLogHertz = 1000.0
            val minLogMel = 15.0
            val logStep = 27.0 / ln(6.4)
            return if (hz >= minLogHertz) minLogMel + ln(hz / minLogHertz) * logStep else 3.0 * hz / 200.0
        }

        private fun melToHertz(mel: Double): Double {
            val minLogHertz = 1000.0
            val minLogMel = 15.0
            val logStep = ln(6.4) / 27.0
            return if (mel >= minLogMel) minLogHertz * exp(logStep * (mel - minLogMel)) else 200.0 * mel / 3.0
        }
    }

    /** Sparse filter bank: the first contributing FFT bin per filter, and its weights from there. */
    private val melStart = IntArray(N_MELS)
    private val melWeights: Array<FloatArray>

    /** Periodic Hann, matching `window_function(1024, "hann")` with `periodic=True`. */
    private val window = FloatArray(N_FFT) { i -> (0.5 * (1.0 - cos(2.0 * PI * i / N_FFT))).toFloat() }

    // Twiddle factors and the bit-reversal permutation depend only on N_FFT, so they are built once
    // rather than recomputed for each of the 1001 frames of every track.
    private val bitReversal = IntArray(N_FFT)
    private val twiddleReal = FloatArray(N_FFT / 2)
    private val twiddleImag = FloatArray(N_FFT / 2)

    // Reused across frames and tracks to keep the scan out of the allocator.
    private val fftReal = FloatArray(N_FFT)
    private val fftImag = FloatArray(N_FFT)
    private val powerSpectrum = FloatArray(SPECTRUM_BINS)
    private val frameBuffer = FloatArray(PADDED_SAMPLES)
    private val output = FloatArray(FEATURE_SIZE)

    init {
        melWeights = createMelFilterBank()
        for (i in 0 until N_FFT) {
            var reversed = 0
            var value = i
            for (bit in 0 until Integer.numberOfTrailingZeros(N_FFT)) {
                reversed = (reversed shl 1) or (value and 1)
                value = value shr 1
            }
            bitReversal[i] = reversed
        }
        for (i in 0 until N_FFT / 2) {
            val angle = -2.0 * PI * i / N_FFT
            twiddleReal[i] = cos(angle).toFloat()
            twiddleImag[i] = sin(angle).toFloat()
        }
    }

    /**
     * @return log-mel features flattened as [frames * mels], in decibels. The array is reused
     *   between calls, so callers must consume it before the next invocation.
     */
    fun extractFeatures(audioData: FloatArray): FloatArray {
        fillCentredFrameBuffer(audioData)

        for (frame in 0 until NUM_FRAMES) {
            val offset = frame * HOP_LENGTH
            for (i in 0 until N_FFT) {
                fftReal[i] = frameBuffer[offset + i] * window[i]
                fftImag[i] = 0f
            }
            fft()

            for (i in 0 until SPECTRUM_BINS) {
                powerSpectrum[i] = fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i]
            }

            val rowOffset = frame * N_MELS
            for (m in 0 until N_MELS) {
                val weights = melWeights[m]
                val start = melStart[m]
                var acc = 0f
                for (k in weights.indices) {
                    acc += powerSpectrum[start + k] * weights[k]
                }
                // mel_floor, then power_to_db with reference 1.0 and no dynamic-range clamp.
                output[rowOffset + m] = 10f * log10(max(acc, MIN_POWER))
            }
        }
        return output
    }

    /**
     * Lays the excerpt out the way the reference does: repeat-and-pad anything shorter than ten
     * seconds, then reflect half a window at each end so frame `t` is centred on sample `t * hop`.
     */
    private fun fillCentredFrameBuffer(audioData: FloatArray) {
        val source = frameBuffer
        if (audioData.isEmpty()) {
            source.fill(0f)
            return
        }

        // repeatpad: whole repetitions of the clip, then silence for the remainder.
        var written = 0
        while (written + audioData.size <= NUM_SAMPLES) {
            audioData.copyInto(source, PAD + written, 0, audioData.size)
            written += audioData.size
            if (audioData.size >= NUM_SAMPLES) break
        }
        if (written == 0) {
            audioData.copyInto(source, PAD, 0, min(audioData.size, NUM_SAMPLES))
            written = min(audioData.size, NUM_SAMPLES)
        }
        if (written < NUM_SAMPLES) source.fill(0f, PAD + written, PAD + NUM_SAMPLES)

        // numpy's "reflect": the edge sample itself is not repeated.
        for (i in 0 until PAD) {
            source[PAD - 1 - i] = source[PAD + 1 + i]
            source[PAD + NUM_SAMPLES + i] = source[PAD + NUM_SAMPLES - 2 - i]
        }
    }

    /** In-place radix-2 FFT over [fftReal]/[fftImag], using the precomputed tables. */
    private fun fft() {
        for (i in 0 until N_FFT) {
            val j = bitReversal[i]
            if (i < j) {
                var tmp = fftReal[i]; fftReal[i] = fftReal[j]; fftReal[j] = tmp
                tmp = fftImag[i]; fftImag[i] = fftImag[j]; fftImag[j] = tmp
            }
        }

        var len = 2
        while (len <= N_FFT) {
            val half = len shr 1
            val step = N_FFT / len
            var i = 0
            while (i < N_FFT) {
                var twiddle = 0
                for (k in 0 until half) {
                    val wR = twiddleReal[twiddle]
                    val wI = twiddleImag[twiddle]
                    val lo = i + k
                    val hi = lo + half
                    val vR = fftReal[hi] * wR - fftImag[hi] * wI
                    val vI = fftReal[hi] * wI + fftImag[hi] * wR
                    fftReal[hi] = fftReal[lo] - vR
                    fftImag[hi] = fftImag[lo] - vI
                    fftReal[lo] += vR
                    fftImag[lo] += vI
                    twiddle += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Slaney-scaled triangular filters on fractional bin boundaries, area-normalised so each filter
     * carries roughly constant energy — `mel_filter_bank(..., norm="slaney", mel_scale="slaney")`.
     */
    private fun createMelFilterBank(): Array<FloatArray> {
        val fftFreqs = DoubleArray(SPECTRUM_BINS) { it * (SAMPLE_RATE / 2.0) / (SPECTRUM_BINS - 1) }
        val melMin = hertzToMel(F_MIN)
        val melMax = hertzToMel(F_MAX)
        val filterFreqs = DoubleArray(N_MELS + 2) { melToHertz(melMin + it * (melMax - melMin) / (N_MELS + 1)) }

        return Array(N_MELS) { m ->
            val lower = filterFreqs[m]
            val centre = filterFreqs[m + 1]
            val upper = filterFreqs[m + 2]
            val enorm = 2.0 / (upper - lower)

            var first = -1
            var last = -1
            val values = DoubleArray(SPECTRUM_BINS)
            for (i in 0 until SPECTRUM_BINS) {
                val down = (fftFreqs[i] - lower) / (centre - lower)
                val up = (upper - fftFreqs[i]) / (upper - centre)
                val value = max(0.0, min(down, up)) * enorm
                values[i] = value
                if (value > 0.0) {
                    if (first < 0) first = i
                    last = i
                }
            }
            if (first < 0) {
                melStart[m] = 0
                FloatArray(0)
            } else {
                melStart[m] = first
                FloatArray(last - first + 1) { values[first + it].toFloat() }
            }
        }
    }
}
