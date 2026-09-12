package music.ai.recommend.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pins the log-mel features to `ClapFeatureExtractor`.
 *
 * Expected values come from transformers 4.36 — `spectrogram(..., power=2.0, log_mel="dB")` over
 * `mel_filter_bank(513, 64, 50, 14000, 48000, norm="slaney", mel_scale="slaney")` — run on the same
 * deterministic signal this test generates. Every parameter of the extractor is load-bearing, so a
 * drift in any of them shows up here rather than as quietly worse recommendations.
 */
@RunWith(AndroidJUnit4::class)
class AudioProcessorTest {

    /** Pure tones only: no RNG, so the reference and the device see identical input. */
    private fun signal(n: Int = AudioProcessor.NUM_SAMPLES) = FloatArray(n) { i ->
        (0.4 * sin(2 * PI * 440 * i / 48000.0) +
            0.2 * sin(2 * PI * 3150 * i / 48000.0) +
            0.05 * sin(2 * PI * 11000 * i / 48000.0)).toFloat()
    }

    @Test
    fun matchesReferenceFeatures() {
        val features = AudioProcessor().extractFeatures(signal())
        assertEquals(AudioProcessor.FEATURE_SIZE, features.size)

        // Single-precision FFT against the reference's float64, so a few hundredths of a dB.
        val tolerance = 0.05f
        val expected = mapOf(
            0 to 7.2610f,
            64 to -35.5696f,
            1000 to -58.0551f,
            5000 to 2.1827f,
            20000 to -70.9417f,
            32032 to -72.9799f,
            45000 to 2.1827f,
            60000 to -69.5069f,
            64063 to -35.8436f
        )
        for ((index, value) in expected) {
            assertEquals("feature[$index]", value, features[index], tolerance)
        }
    }

    @Test
    fun matchesReferenceStatistics() {
        val features = AudioProcessor().extractFeatures(signal())
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var sum = 0.0
        for (v in features) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        assertEquals("min", -100.0f, min, 0.01f)       // the -100 dB floor of power_to_db
        assertEquals("max", 22.3309f, max, 0.05f)
        assertEquals("mean", -56.5688f, (sum / features.size).toFloat(), 0.05f)
    }

    /**
     * Anything shorter than ten seconds is repeated whole and the remainder zero-filled, as the
     * reference's "repeatpad" does. A length that does not divide evenly leaves a silent tail; one
     * that does (480000 / 3, say) leaves none, which is why this picks the former.
     */
    @Test
    fun repeatsShortInputAndPadsTheRemainder() {
        val short = signal(172_345) // 2 whole repeats, then 135 310 samples of silence
        val features = AudioProcessor().extractFeatures(short)
        assertEquals(AudioProcessor.FEATURE_SIZE, features.size)
        assertEquals("silent tail", -100.0f, features[features.size - 1], 0.01f)
    }

    /** A clip that tiles exactly is not padded at all, so nothing is silent. */
    @Test
    fun doesNotPadWhenRepeatsFillExactly() {
        val features = AudioProcessor().extractFeatures(signal(AudioProcessor.NUM_SAMPLES / 3))
        assertEquals(AudioProcessor.FEATURE_SIZE, features.size)
        assertNotEquals(-100.0f, features[features.size - 1], 0.01f)
    }
}
