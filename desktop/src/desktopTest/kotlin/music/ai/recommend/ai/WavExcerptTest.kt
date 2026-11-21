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
 * The guard against the two copies of the wav reader drifting apart.
 *
 * Nothing is committed here: the fixture is written by this test, from integers only. That is on
 * purpose and it is the stronger choice — a wav of tones would need `Math.sin`, which is allowed
 * to differ by an unit in the last place between two implementations, and a fixture that is not
 * the same file on both platforms cannot guard anything. A counter and a few shifts are the same
 * numbers everywhere.
 *
 * What the digests pin is the whole path: the chunk walk, the sample widths, the mixing down of the
 * channels and the resampling. The audio is deliberately awkward — alternating loud and quiet
 * halves, the two channels different — so that a mistake in any of those shows up rather than
 * averaging away.
 */
class WavExcerptTest {

    private val rate = 44_100
    private val seconds = 25

    @Test
    fun theReaderReturnsExactlyOneExcerptAndItIsWhatItWas() {
        val file = write(bits = 16)
        try {
            val samples = WavExcerpt.decode(file)
            assertEquals(Excerpt.SAMPLES, samples.size)
            println("wav 16-bit digest: ${digestOf(samples)} peak: ${samples.maxOf { kotlin.math.abs(it) }}")
            assertEquals("c117eeac24dc8c608584f4b81ae3dd5c27a9fcd4e0d6a2e84533d49d5caeab94", digestOf(samples))
        } finally {
            file.delete()
        }
    }

    @Test
    fun theWindowIsNamedByTheSizeOfTheAudioItself() {
        val file = write(bits = 16)
        try {
            // Bytes of audio divided by bytes of a frame: the one length in this app that is
            // beyond argument, which is what lets a wav vector travel.
            assertNotNull(WavExcerpt.startMs(file))
            assertEquals(7_000L, WavExcerpt.startMs(file))
            assertEquals(WavExcerpt.startMs(file), SharedDecoder.startMs(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun theSameAudioInThreeWidthsDecodesToTheSameExcerpt() {
        // Twenty-four and thirty-two bit are the same numbers shifted up, so the excerpt must come
        // out the same to the precision floats have. This is what says the widths are being read
        // rather than guessed — a reader that took 24-bit for 16 would be out by a factor of 256.
        val sixteen = write(bits = 16)
        val twentyFour = write(bits = 24)
        val thirtyTwo = write(bits = 32)
        try {
            val a = WavExcerpt.decode(sixteen)
            val b = WavExcerpt.decode(twentyFour)
            val c = WavExcerpt.decode(thirtyTwo)
            assertEquals(a.size, b.size)
            assertEquals(a.size, c.size)
            for (index in a.indices) {
                assertEquals(a[index], b[index], 1e-6f, "образец $index")
                assertEquals(a[index], c[index], 1e-6f, "образец $index")
            }
        } finally {
            sixteen.delete()
            twentyFour.delete()
            thirtyTwo.delete()
        }
    }

    @Test
    fun aFileThatIsNotThereOrIsNotAWavIsRefusedRatherThanGuessedAt() {
        assertNull(WavExcerpt.startMs(File("src/desktopTest/resources/нет такого.wav")))
        assertEquals(0, WavExcerpt.decode(File("src/desktopTest/resources/no-such-file.wav")).size)
        assertNull(WavExcerpt.startMs(File("src/desktopTest/resources/mp3_excerpt_fixture.mp3")))
        assertEquals(0, WavExcerpt.decode(File("src/desktopTest/resources/mp3_excerpt_fixture.mp3")).size)
    }

    @Test
    fun onlyWavGoesThroughThisReader() {
        assertTrue(WavExcerpt.handles("/music/a.wav"))
        assertTrue(WavExcerpt.handles("/music/A - B.WAVE"))
        assertTrue(!WavExcerpt.handles("/music/a.flac"))
        assertTrue(!WavExcerpt.handles("/music/wav"))
        assertTrue(!WavExcerpt.handles(""))
    }

    private fun digestOf(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Writes a stereo wav of [bits] width, whose samples come from a counter rather than from a
     * waveform so that the file is the same file wherever this test runs.
     */
    private fun write(bits: Int): File {
        val frames = rate * seconds
        val bytesPerSample = bits / 8
        val data = ByteArray(frames * 2 * bytesPerSample)
        var at = 0
        var state = 1
        for (frame in 0 until frames) {
            // A plain linear congruence, kept small, and quieter for one second in every two so
            // that an excerpt from the middle is not the same as one from anywhere else.
            state = (state * 1103515245 + 12345) and 0x3FFFFFFF
            val loud = (frame / rate) % 2 == 0
            val left = ((state shr 14) and 0x7FFF) - 0x4000
            val right = (((state shr 7) and 0x3FFF) - 0x2000)
            put(data, at, if (loud) left else left / 8, bits); at += bytesPerSample
            put(data, at, if (loud) right else right / 8, bits); at += bytesPerSample
        }
        val file = File.createTempFile("aura-wav-$bits-", ".wav")
        file.outputStream().buffered().use { out ->
            out.write("RIFF".toByteArray())
            out.write(little(36 + data.size, 4))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(little(16, 4))
            out.write(little(1, 2))                                  // plain integer samples
            out.write(little(2, 2))                                  // channels
            out.write(little(rate, 4))
            out.write(little(rate * 2 * bytesPerSample, 4))
            out.write(little(2 * bytesPerSample, 2))
            out.write(little(bits, 2))
            out.write("data".toByteArray())
            out.write(little(data.size, 4))
            out.write(data)
        }
        return file
    }

    /** The sample, shifted up to the width being written, so the three files hold one signal. */
    private fun put(data: ByteArray, at: Int, value: Int, bits: Int) {
        val scaled = value.toLong() shl (bits - 16)
        for (index in 0 until bits / 8) {
            data[at + index] = ((scaled shr (8 * index)) and 0xFF).toByte()
        }
    }

    private fun little(value: Int, count: Int): ByteArray =
        ByteArray(count) { ((value shr (8 * it)) and 0xFF).toByte() }
}
