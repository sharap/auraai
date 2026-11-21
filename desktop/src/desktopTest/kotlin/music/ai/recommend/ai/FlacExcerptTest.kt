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
 * The guard against the two copies of the flac decoder drifting apart.
 *
 * The desktop port runs this same test on the same fixture, and an embedding only travels between
 * devices if both produced the same samples — so what is checked is not "roughly the right audio"
 * but the exact bytes. If this fails after a change, every embedding made from a flac has stopped
 * being comparable with one made elsewhere, and the decoder's name has to change with it.
 *
 * Correctness against the world is a different test and a stronger one: `FlacAgainstFfmpegTest` on
 * the desktop decodes real files and compares them with ffmpeg sample for sample, which for a
 * lossless format is an oracle rather than a second opinion. At the time of writing it agreed on
 * all 425 flac files in the library it was run against.
 *
 * The fixture is 25 seconds of stereo tones, generated once and committed, with the two channels
 * deliberately different — 440 Hz at nine tenths against 660 Hz at two thirds — so that a mistake
 * in mixing them down cannot pass unnoticed.
 */
class FlacExcerptTest {

    private val fixture = File("src/desktopTest/resources/flac_excerpt_fixture.flac")

    @Test
    fun theDecoderReturnsExactlyOneExcerpt() {
        assertEquals(Excerpt.SAMPLES, FlacExcerpt.decode(fixture).size)
    }

    @Test
    fun theDecodedExcerptIsByteForByteWhatItWas() {
        val samples = FlacExcerpt.decode(fixture)
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.array())
        println("flac fixture digest: " + digest.joinToString("") { "%02x".format(it) })
        assertEquals(
            "e8471884664e1c3dd2a547d41b46fc2b336a30d2ebdaf3dc4ce0effe8676c40e",
            digest.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun theExcerptIsAudioAndNotNoiseOrSilence() {
        // A buffer filled in the wrong order still hashes to something; these say it is the signal
        // that was put in. The two channels peak at 0.90 and 0.67 and are at different
        // frequencies, so their average cannot reach either.
        val samples = FlacExcerpt.decode(fixture)
        val peak = samples.maxOf { kotlin.math.abs(it) }
        println("flac fixture peak: $peak energy: " + samples.sumOf { (it * it).toDouble() })
        assertTrue(peak in 0.70f..0.79f, "пик $peak")
        val energy = samples.sumOf { (it * it).toDouble() }
        assertEquals(75457.86, energy, 0.1)

    }

    @Test
    fun theWindowIsNamedByTheStreamsOwnCountOfItsSamples() {
        // The number that travels with a vector, and it has to be the one [decode] chose the
        // window by. The header says how many samples there are, so this is arithmetic and not an
        // estimate — which is the whole reason a flac vector can travel at all.
        val named = FlacExcerpt.startMs(fixture)
        assertNotNull(named)
        // Twenty-five seconds of frames, so the middle ten begin at 7 s: 25 / 2 = 12, less 5.
        assertEquals(7_000L, named)
    }

    @Test
    fun aFileThatIsNotThereOrIsNotFlacIsRefusedRatherThanGuessedAt() {
        assertNull(FlacExcerpt.startMs(File("src/desktopTest/resources/нет такого.flac")))
        assertEquals(0, FlacExcerpt.decode(File("src/desktopTest/resources/no-such-file.flac")).size)
        // An mp3 is not a flac however it is named, and the magic says so before anything else.
        assertNull(FlacExcerpt.startMs(File("src/desktopTest/resources/mp3_excerpt_fixture.mp3")))
        assertEquals(0, FlacExcerpt.decode(File("src/desktopTest/resources/mp3_excerpt_fixture.mp3")).size)
    }

    @Test
    fun onlyFlacGoesThroughThisDecoder() {
        assertTrue(FlacExcerpt.handles("/music/a.flac"))
        assertTrue(FlacExcerpt.handles("/music/A - B (Remix).FLAC"))
        assertTrue(!FlacExcerpt.handles("/music/a.mp3"))
        assertTrue(!FlacExcerpt.handles("/music/a.m4a"))
        assertTrue(!FlacExcerpt.handles("/music/flac"))
        assertTrue(!FlacExcerpt.handles(""))
    }

    @Test
    fun theSharedDecoderSendsEachFormatToTheRightPlace() {
        assertEquals(Mp3Excerpt.DECODER, SharedDecoder.of("/music/a.mp3"))
        assertEquals(FlacExcerpt.DECODER, SharedDecoder.of("/music/a.flac"))
        assertEquals(WavExcerpt.DECODER, SharedDecoder.of("/music/a.wav"))
        assertEquals(M4aExcerpt.DECODER, SharedDecoder.of("/music/a.m4a"))
        // Not a guess for the ones nobody shares: null means "the platform decodes this, and its
        // vectors stay at home".
        assertNull(SharedDecoder.of("/music/a.ogg"))
        assertNull(SharedDecoder.of("/music/a.wma"))
        assertEquals(
            setOf(Mp3Excerpt.DECODER, FlacExcerpt.DECODER, WavExcerpt.DECODER, M4aExcerpt.DECODER),
            SharedDecoder.NAMES
        )
        // And what it decodes is what the format's own decoder decodes, not something adjacent.
        assertEquals(
            FlacExcerpt.decode(fixture).toList(),
            SharedDecoder.decode(fixture).toList()
        )
        assertEquals(FlacExcerpt.startMs(fixture), SharedDecoder.startMs(fixture))
    }
}
