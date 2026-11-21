package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * This device's copy of every playlist change it knows of.
 *
 * The log is kept rather than the state, because a change that arrives late belongs in the middle
 * of the order and only a log can put it there. So what matters here is that it survives being
 * written and read, that the same change is not taken twice, and that a line somebody else's
 * version wrote does not cost the rest of it.
 */
class PlaylistLogTest {

    private val phone = "aa".repeat(16)
    private val desktop = "bb".repeat(16)
    private val now = 1_793_880_000_000L

    private fun logFile() = File(
        File(System.getProperty("java.io.tmpdir"), "aura-playlist-log-${System.nanoTime()}"),
        "playlist-log.jsonl"
    )

    private fun op(seq: Long, device: String = phone, title: String = "один", playlist: String? = null) =
        PlaylistOp(
            PlaylistOp.Kind.ADD, seq, device, now, playlist,
            TrackKey(null, 200_000, "мельница", title)
        )

    @Test
    fun whatIsWrittenIsReadBack() {
        val file = logFile()
        val first = PlaylistLog(file)
        assertEquals(2, first.append(listOf(op(1), op(2, title = "два"))))

        val reopened = PlaylistLog(file)
        assertEquals(listOf(1L, 2L), reopened.all().map { it.seq })
        assertEquals(listOf("один", "два"), reopened.all().map { it.track?.title })
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun theSameChangeIsNotTakenTwice() {
        // A folder read again offers the same operations, and they are the same ones rather than
        // new ones: the device that made a change and its counter say so.
        val file = logFile()
        val log = PlaylistLog(file)
        assertEquals(2, log.append(listOf(op(1), op(2, device = desktop))))
        assertEquals(0, log.append(listOf(op(1), op(2, device = desktop))))
        assertEquals(2, log.all().size)

        // The same counter from a different device is a different change.
        assertEquals(1, log.append(listOf(op(1, device = desktop))))
        assertEquals(3, PlaylistLog(file).all().size)
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun theCounterIsOneMoreThanAnythingInIt() {
        val log = PlaylistLog(logFile())
        assertEquals(1L, log.nextSeq())
        log.append(listOf(op(1), op(9, device = desktop)))
        assertEquals(10L, log.nextSeq())
    }

    @Test
    fun theOutboxSeesOnlyThisDevicesOwnAndOnlyPastTheWatermark() {
        val log = PlaylistLog(logFile())
        log.append(listOf(op(1), op(2, device = desktop), op(3), op(4)))
        assertEquals(listOf(1L, 3L, 4L), log.own(phone, afterSeq = 0, limit = 10).map { it.seq })
        assertEquals(listOf(3L, 4L), log.own(phone, afterSeq = 1, limit = 10).map { it.seq })
        assertEquals(listOf(3L), log.own(phone, afterSeq = 1, limit = 1).map { it.seq })
        assertEquals(listOf(2L), log.own(desktop, afterSeq = 0, limit = 10).map { it.seq })
    }

    @Test
    fun aLineFromSomewhereElseCostsOnlyItself() {
        val file = logFile()
        file.parentFile?.mkdirs()
        file.writeText(
            listOf(
                op(1).toJson().toString(),
                "не json вовсе",
                """{"k":"reorder","s":2,"d":"$phone","at":$now,"p":"p"}""",
                "",
                op(3, title = "три").toJson().toString()
            ).joinToString("\n", postfix = "\n")
        )
        val log = PlaylistLog(file)
        assertEquals(listOf(1L, 3L), log.all().map { it.seq })
        // And the next counter looks past the lines it could not read, so a change of its own does
        // not collide with one it skipped.
        assertEquals(4L, log.nextSeq())
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun aLogThatCannotBeWrittenSaysSoRatherThanLosingTheChanges() {
        // Null and not zero: zero means "they were all already here", which is a pass that
        // succeeded. The caller has to tell those apart, or it marks a folder read that it failed
        // to take anything from.
        val directory = File(System.getProperty("java.io.tmpdir"), "aura-log-readonly-${System.nanoTime()}")
        directory.mkdirs()
        val file = File(directory, "playlist-log.jsonl")
        val log = PlaylistLog(file)
        assertEquals(1, log.append(listOf(op(1))))
        directory.setWritable(false)
        file.setWritable(false)
        try {
            assertNull(log.append(listOf(op(2, title = "два"))))
            // And it is not held in memory either, so the next pass meets it again.
            assertEquals(listOf(1L), log.all().map { it.seq })
        } finally {
            file.setWritable(true)
            directory.setWritable(true)
            directory.deleteRecursively()
        }
    }

    @Test
    fun anEmptyAppendIsNotAFailure() {
        val log = PlaylistLog(logFile())
        assertEquals(0, log.append(emptyList()))
        assertTrue(log.all().isEmpty())
    }
}
