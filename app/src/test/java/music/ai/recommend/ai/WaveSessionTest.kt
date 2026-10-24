package music.ai.recommend.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class WaveSessionTest {

    private val random = Random(11)

    private fun vector(dims: Int = 512) = FloatArray(dims) { random.nextFloat() * 2 - 1 }

    private fun session(avoid: FloatArray? = vector(), description: String? = "спокойное") = WaveSession(
        mode = "DESCRIPTION",
        description = description,
        state = WaveState(
            direction = vector(),
            centre = vector(),
            avoid = avoid,
            played = setOf(7L, 11L, 13L),
            recentGroups = listOf("мельница", "björk", ""),
            steps = 9,
            rejections = 2
        ),
        savedAt = 1_700_000_000_000L
    )

    private fun roundTrip(session: WaveSession): WaveSession? {
        val out = ByteArrayOutputStream()
        WaveSessions.write(out, session)
        return WaveSessions.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun aWaveSurvivesBeingWrittenDown() {
        val original = session()
        val read = roundTrip(original)!!

        assertEquals(original.mode, read.mode)
        assertEquals(original.description, read.description)
        assertEquals(original.savedAt, read.savedAt)
        assertEquals(original.state.steps, read.state.steps)
        assertEquals(original.state.rejections, read.state.rejections)
        assertEquals(original.state.played, read.state.played)
        assertEquals(original.state.recentGroups, read.state.recentGroups)
        assertArrayEquals(original.state.direction, read.state.direction, 0f)
        assertArrayEquals(original.state.centre, read.state.centre, 0f)
        assertArrayEquals(original.state.avoid, read.state.avoid, 0f)
    }

    /** Nothing has been rejected yet, and no description for a wave that follows the taste. */
    @Test
    fun theOptionalPartsSurviveBeingAbsent() {
        val read = roundTrip(session(avoid = null, description = null))!!
        assertNull(read.state.avoid)
        assertNull(read.description)
    }

    /**
     * A wave is a convenience: a file left half-written by a process that was killed, or one from
     * an older version, must read as "no wave" and never as a crash on startup.
     */
    @Test
    fun aTruncatedFileIsNoWave() {
        val out = ByteArrayOutputStream()
        WaveSessions.write(out, session())
        val bytes = out.toByteArray()
        assertNull(WaveSessions.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2))))
        assertNull(WaveSessions.read(ByteArrayInputStream(ByteArray(0))))
        assertNull(WaveSessions.read(ByteArrayInputStream(ByteArray(64) { 0x5a })))
    }

    /** Six kilobytes or so: it is written on every track, so the size is part of the design. */
    @Test
    fun theRecordStaysSmall() {
        val out = ByteArrayOutputStream()
        WaveSessions.write(out, session())
        assertTrue("${out.size()} bytes", out.size() in 5_000..8_000)
    }
}
