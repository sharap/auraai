package music.ai.recommend.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The guard against the two copies of the m4a decoder drifting apart.
 *
 * Of the four shared decoders this is the one that could not have been avoided by being careful.
 * AAC is lossy and its specification says how close a decoder has to be rather than what it has to
 * output, so two correct decoders of the same file produce two different signals on purpose —
 * there is nothing to align. Both apps therefore run the same library, and this says they still
 * do: the digest below is of the samples, and if it changes, every embedding made from an m4a has
 * stopped being comparable with one made elsewhere.
 *
 * That it is the *right* audio is a different test: `M4aAgainstFfmpegTest` on the desktop compares
 * the excerpt with ffmpeg's over real files, where the measure is the cosine rather than equality.
 * Over the 133 m4a files it was written for, the mean was 0.9999 and the worst 0.9982 — and the
 * same files, decoded on a phone and on a desktop, gave identical digests.
 *
 * The fixture is the tones from [FlacExcerptTest], encoded to AAC-LC at 128 kbit — so it also
 * exercises an edit list, which is where an encoder says how much of the front of its output is
 * priming rather than music.
 */
class M4aExcerptTest {

    private val fixture = File("src/desktopTest/resources/m4a_excerpt_fixture.m4a")

    @Test
    fun theDecoderReturnsExactlyOneExcerpt() {
        assertEquals(Excerpt.SAMPLES, M4aExcerpt.decode(fixture).size)
    }

    @Test
    fun theDecodedExcerptIsByteForByteWhatItWas() {
        val samples = M4aExcerpt.decode(fixture)
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.array())
        println("m4a fixture digest: " + digest.joinToString("") { "%02x".format(it) })
        assertEquals("a73b19f7d2aa2376786a7cd5f82a9653dd9c2a73e52fc306a7f04e3e42724d30", digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun theExcerptIsTheToneThatWasEncoded() {
        // The fixture is 440 Hz at nine tenths against 660 Hz at two thirds, and a lossy encoder
        // keeps that nearly exactly: a peak far from this would mean the wrong ten seconds, the
        // wrong channels, or the wrong scale.
        val samples = M4aExcerpt.decode(fixture)
        val peak = samples.maxOf { kotlin.math.abs(it) }
        println("m4a fixture peak: $peak energy: " + samples.sumOf { (it * it).toDouble() })
        assertTrue(peak in 0.70f..0.80f, "пик $peak")
    }

    @Test
    fun theWindowIsNamedByTheContainersOwnCountOfTheAudio() {
        val named = M4aExcerpt.startMs(fixture)
        assertNotNull(named)
        // Twenty-five seconds of music, so the middle ten begin at 7 s — and the priming the
        // encoder added in front of it is not music and must not count towards the middle.
        assertEquals(7_000L, named)
    }

    @Test
    fun aFileThatIsNotThereOrIsNotAnM4aIsRefusedRatherThanGuessedAt() {
        assertNull(M4aExcerpt.startMs(File("src/desktopTest/resources/нет такого.m4a")))
        assertEquals(0, M4aExcerpt.decode(File("src/desktopTest/resources/no-such-file.m4a")).size)
        assertNull(M4aExcerpt.startMs(File("src/desktopTest/resources/mp3_excerpt_fixture.mp3")))
        assertEquals(0, M4aExcerpt.decode(File("src/desktopTest/resources/flac_excerpt_fixture.flac")).size)
    }

    @Test
    fun onlyM4aGoesThroughThisDecoder() {
        assertTrue(M4aExcerpt.handles("/music/a.m4a"))
        assertTrue(M4aExcerpt.handles("/music/A - B.M4A"))
        assertTrue(M4aExcerpt.handles("/music/book.m4b"))
        assertTrue(!M4aExcerpt.handles("/music/a.mp4"))
        assertTrue(!M4aExcerpt.handles("/music/a.aac"))
        assertTrue(!M4aExcerpt.handles(""))
    }
}
