package music.ai.recommend.ai

import kotlin.math.*

class AudioProcessor {
    companion object {
        private const val SAMPLE_RATE = 48000
        private const val N_FFT = 1024
        private const val HOP_LENGTH = 480
        private const val N_MELS = 64
        private const val F_MIN = 0.0f
        private const val F_MAX = 14000.0f
    }

    private val melBasis: Array<FloatArray> by lazy {
        createMelFilterbank(N_MELS, N_FFT, SAMPLE_RATE, F_MIN, F_MAX)
    }

    private val window: FloatArray by lazy {
        FloatArray(N_FFT) { i -> 0.5f * (1 - cos(2.0 * PI * i / (N_FFT - 1)).toFloat()) }
    }

    // Reuse buffers to reduce GC pressure
    private val fftInputReal = FloatArray(N_FFT)
    private val fftInputImag = FloatArray(N_FFT)
    private val powerSpectrum = FloatArray(N_FFT / 2 + 1)

    fun extractFeatures(audioData: FloatArray): FloatArray {
        // To get exactly 1001 frames with N_FFT=1024 and HOP_LENGTH=480:
        // (1001 - 1) * 480 + 1024 = 481024 samples
        val targetSamples = 481024 
        val paddedAudio = if (audioData.size >= targetSamples) {
            audioData.sliceArray(0 until targetSamples)
        } else {
            FloatArray(targetSamples).apply {
                audioData.copyInto(this)
            }
        }

        val spectrogram = computeMelSpectrogram(paddedAudio)
        return logMel(spectrogram)
    }

    private fun computeMelSpectrogram(audio: FloatArray): Array<FloatArray> {
        val numFrames = 1 + (audio.size - N_FFT) / HOP_LENGTH
        val melSpectrogram = Array(numFrames) { FloatArray(N_MELS) }

        for (f in 0 until numFrames) {
            val offset = f * HOP_LENGTH
            
            for (i in 0 until N_FFT) {
                fftInputReal[i] = audio[offset + i] * window[i]
                fftInputImag[i] = 0f
            }
            
            fft(fftInputReal, fftInputImag)
            
            for (i in powerSpectrum.indices) {
                powerSpectrum[i] = fftInputReal[i] * fftInputReal[i] + fftInputImag[i] * fftInputImag[i]
            }
            
            for (m in 0 until N_MELS) {
                var melValue = 0.0f
                for (i in powerSpectrum.indices) {
                    melValue += powerSpectrum[i] * melBasis[m][i]
                }
                melSpectrogram[f][m] = melValue
            }
        }
        return melSpectrogram
    }

    private fun logMel(melSpec: Array<FloatArray>): FloatArray {
        val flattened = FloatArray(melSpec.size * N_MELS)
        for (f in melSpec.indices) {
            for (m in 0 until N_MELS) {
                // Better Log-Mel mapping for CLAP: log(x + 1e-10)
                // CLAP expects specific range, let's use natural log or log10 consistently
                flattened[f * N_MELS + m] = ln(melSpec[f][m] + 1e-10f)
            }
        }
        return flattened
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n <= 1) return

        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenR = cos(ang).toFloat()
            val wlenI = sin(ang).toFloat()
            for (i in 0 until n step len) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wR - imag[i + k + len / 2] * wI
                    val vI = real[i + k + len / 2] * wI + imag[i + k + len / 2] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWR = wR * wlenR - wI * wlenI
                    wI = wR * wlenI + wI * wlenR
                    wR = nextWR
                }
            }
            len = len shl 1
        }
    }

    private fun createMelFilterbank(numMels: Int, nFft: Int, sampleRate: Int, fMin: Float, fMax: Float): Array<FloatArray> {
        val melBasis = Array(numMels) { FloatArray(nFft / 2 + 1) }
        fun hzToMel(hz: Float): Float = 2595.0f * log10(1.0f + hz / 700.0f)
        fun melToHz(mel: Float): Float = 700.0f * (10.0f.pow(mel / 2595.0f) - 1.0f)

        val minMel = hzToMel(fMin)
        val maxMel = hzToMel(fMax)
        val melPoints = FloatArray(numMels + 2) { i -> melToHz(minMel + i * (maxMel - minMel) / (numMels + 1)) }
        val binPoints = IntArray(numMels + 2) { i -> ((nFft + 1) * melPoints[i] / sampleRate).toInt() }
        
        for (m in 1..numMels) {
            for (i in binPoints[m - 1] until binPoints[m]) {
                melBasis[m - 1][i] = (i - binPoints[m - 1]).toFloat() / (binPoints[m] - binPoints[m - 1])
            }
            for (i in binPoints[m] until binPoints[m + 1]) {
                if (i < melBasis[m - 1].size) {
                    melBasis[m - 1][i] = (binPoints[m + 1] - i).toFloat() / (binPoints[m + 1] - binPoints[m])
                }
            }
        }
        return melBasis
    }
}
