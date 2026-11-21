package music.ai.recommend.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guard against the two copies of the decoder drifting apart, from this side.
 *
 * The Android app runs the same [Mp3Excerpt] on the same committed fixture and asserts the same
 * sha256. That pair of tests is the whole reason an mp3 embedding may be carried between the two:
 * not "roughly the right audio" but the exact bytes. If this fails after a change, every stored
 * embedding made from an mp3 has stopped being comparable with one made elsewhere, and the analysis
 * version has to be raised.
 *
 * The fixture is a 25-second stereo mp3 of plain tones, with the two channels deliberately
 * different so that a mistake in mixing them down cannot pass unnoticed.
 */
class Mp3ExcerptTest {

    private val fixture = File("src/desktopTest/resources/mp3_excerpt_fixture.mp3")

    @Test
    fun theDecoderReturnsExactlyOneExcerpt() {
        assertEquals(Excerpt.SAMPLES, Mp3Excerpt.decode(fixture).size)
    }

    @Test
    fun theDecodedExcerptIsByteForByteWhatItIsOnTheOtherSide() {
        val samples = Mp3Excerpt.decode(fixture)
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.array())
        assertEquals(
            "664e031ebf30e962c149c6773db2b2b0c69baae2210575362fe90d19c71ad35b",
            digest.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun theExcerptIsAudioAndNotNoiseOrSilence() {
        val samples = Mp3Excerpt.decode(fixture)
        val peak = samples.maxOf { abs(it) }
        assertTrue(peak in 0.70f..0.79f, "пик $peak")
        assertEquals(55010.18, samples.sumOf { (it * it).toDouble() }, 0.1)
    }

    @Test
    fun theWindowIsNamedByThisDecodersOwnCountOfTheSamples() {
        // The number that travels with a vector. 25 s of frames, so the middle ten begin at 7 s.
        assertEquals(7_000L, Mp3Excerpt.startMs(fixture))
        assertNull(Mp3Excerpt.startMs(File("src/desktopTest/resources/нет такого.mp3")))
    }
}
