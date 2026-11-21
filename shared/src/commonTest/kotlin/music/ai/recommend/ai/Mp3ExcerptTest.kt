package music.ai.recommend.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The guard against the two copies of the decoder drifting apart.
 *
 * The desktop port runs the same [Mp3Excerpt] on the same fixture, and an embedding only travels
 * between devices if both produced the same samples — so what is checked here is not "roughly the
 * right audio" but the exact bytes. If this test fails after a change, every stored embedding made
 * from an mp3 is no longer comparable with one made elsewhere, and the analysis version has to be
 * raised.
 *
 * The fixture is a 25-second stereo mp3 of plain tones, generated once and committed, with the two
 * channels deliberately different so that a mistake in mixing them down cannot pass unnoticed.
 */
class Mp3ExcerptTest {

    private val fixture = Fixtures.of("mp3_excerpt_fixture.mp3")

    @Test
    fun theDecoderReturnsExactlyOneExcerpt() {
        val samples = Mp3Excerpt.decode(fixture)
        assertEquals(Excerpt.SAMPLES, samples.size)
    }

    @Test
    fun theDecodedExcerptIsByteForByteWhatItWas() {
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
        // A buffer filled in the wrong order still hashes to something; these say it is the signal
        // that was put in. The fixture's loudest channel peaks at 0.90 and the quieter one at 0.67,
        // so their average cannot reach either.
        val samples = Mp3Excerpt.decode(fixture)
        val peak = samples.maxOf { kotlin.math.abs(it) }
        assertTrue("пик $peak", peak in 0.70f..0.79f)
        val energy = samples.sumOf { (it * it).toDouble() }
        assertEquals(55010.18, energy, 0.1)
    }

    @Test
    fun theWindowIsNamedByThisDecodersOwnCountOfTheSamples() {
        // The number that travels with a vector, and it has to be the one [decode] chooses the
        // window by. Asking the platform for the track's length instead looks equivalent and is
        // not: a tag reader's whole seconds and a container's milliseconds disagree by a second
        // often enough that about a quarter of a library came out one two-second bucket apart, and
        // every one of those correct vectors was refused as describing a different ten seconds.
        val named = Mp3Excerpt.startMs(fixture)
        assertNotNull(named)

        // The fixture is 25 s of frames, so the middle ten begin at 7 s: 25 / 2 = 12, less 5.
        assertEquals(7_000L, named)

        // And a duration read one second differently — which is what the two platforms do to each
        // other — would have named a different window, one whole bucket away.
        assertEquals(7_000L, Excerpt.startMs(25_000))
        assertEquals(8_000L, Excerpt.startMs(26_000))
    }

    @Test
    fun aFileThatIsNotThereHasNoWindowRatherThanAWrongOne() {
        assertNull(Mp3Excerpt.startMs(Fixtures.missing("нет такого.mp3")))
    }

    @Test
    fun aFileThatIsNotThereDecodesToNothingRatherThanThrowing() {
        assertEquals(0, Mp3Excerpt.decode(Fixtures.missing("no-such-file.mp3")).size)
    }

    @Test
    fun onlyMp3GoesThroughThisDecoder() {
        assertTrue(Mp3Excerpt.handles("/music/a.mp3"))
        assertTrue(Mp3Excerpt.handles("/music/A - B (Remix).MP3"))
        assertTrue(!Mp3Excerpt.handles("/music/a.m4a"))
        assertTrue(!Mp3Excerpt.handles("/music/a.flac"))
        assertTrue(!Mp3Excerpt.handles("/music/mp3"))
        assertTrue(!Mp3Excerpt.handles(""))
    }

    /**
     * The window can be asked for explicitly, and asking for the one the rule would pick anyway
     * gives the same samples — which is what lets the three-window scan reuse this decoder without
     * changing what a single-window vector was made from.
     */
    @Test
    fun askingForTheWindowTheRulePicksChangesNothing() {
        val byRule = Mp3Excerpt.decode(fixture)
        val explicit = Mp3Excerpt.decode(fixture, Mp3Excerpt.startMs(fixture))
        assertArrayEquals(byRule, explicit, 0f)
    }

    @Test
    fun anotherWindowIsAnotherTenSeconds() {
        val middle = Mp3Excerpt.decode(fixture)
        val fromTheStart = Mp3Excerpt.decode(fixture, 0L)
        assertEquals(Excerpt.SAMPLES, fromTheStart.size)
        assertFalse(
            "a window at 0 should not be the window in the middle",
            middle.contentEquals(fromTheStart)
        )
    }
}
