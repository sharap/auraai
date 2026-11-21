package music.ai.recommend.db

import kotlinx.coroutines.runBlocking
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The listening log on this machine, now that sync reads and writes it.
 *
 * Two things were added to it and both are load-bearing. A record says which device heard it, or
 * a listen taken in from the phone would be sent back out as this machine's own and the phone
 * would end up holding it twice under two names. And a record has a number that rises as records
 * are appended, which is how the outbox remembers where it got to — a number that must not depend
 * on a record's position, because pruning drops records from the middle.
 *
 * A log written before any of this exists on real machines, so the conversion is tested too.
 */
class PlayHistoryStoreTest {

    private val phone = "aa".repeat(16)
    private val now = 1_793_880_000_000L
    private val day = 24L * 60 * 60 * 1000

    private lateinit var baseDir: File
    private val historyFile get() = File(baseDir, "play_history.bin")

    @BeforeTest
    fun emptyDirectory() {
        baseDir = File(System.getProperty("java.io.tmpdir"), "aimusic-history-test-${System.nanoTime()}")
        baseDir.mkdirs()
    }

    @AfterTest
    fun tidyUp() {
        baseDir.deleteRecursively()
    }

    private fun dao() = DesktopMusicDao(baseDir)

    private fun listen(path: String, at: Long) = PlayEventEntity(path, at, 180_000, 200_000)

    /** The shape the log had before sync: no header, and four fields a record. */
    private fun writeLegacyLog(vararg events: PlayEventEntity) {
        DataOutputStream(historyFile.outputStream().buffered()).use { out ->
            for (event in events) {
                out.writeUTF(event.path)
                out.writeLong(event.playedAt)
                out.writeLong(event.playedMs)
                out.writeLong(event.durationMs)
            }
        }
    }

    @Test
    fun aListenIsNumberedAndCreditedToThisMachine() = runBlocking {
        val dao = dao()
        dao.appendPlayEvent(listen("/music/один.mp3", now - 2 * 60_000))
        dao.appendPlayEvent(listen("/music/два.mp3", now - 60_000))

        val events = dao.playEvents()
        assertEquals(listOf(1L, 2L), events.map { it.id })
        assertEquals(listOf("", ""), events.map { it.deviceId })
        assertEquals(listOf("/music/один.mp3", "/music/два.mp3"), events.map { it.path })
        assertEquals(2L, dao.newestPlayEventId())
    }

    @Test
    fun numbersCarryOnAfterARestart() = runBlocking {
        dao().appendPlayEvent(listen("/music/один.mp3", now - 60_000))
        // A fresh instance over the same directory, as the next launch of the app.
        val next = dao()
        next.appendPlayEvent(listen("/music/два.mp3", now))
        assertEquals(listOf(1L, 2L), next.playEvents().map { it.id })
    }

    @Test
    fun aLogWrittenBeforeSyncIsConvertedOnce() = runBlocking {
        writeLegacyLog(
            listen("/music/один.mp3", now - 3 * 60_000),
            listen("/music/два.mp3", now - 2 * 60_000),
            listen("/music/три.mp3", now - 60_000)
        )
        val before = historyFile.length()

        val dao = dao()
        val events = dao.playEvents()
        assertEquals(listOf(1L, 2L, 3L), events.map { it.id })
        assertEquals(listOf("", "", ""), events.map { it.deviceId })
        assertEquals(
            listOf("/music/один.mp3", "/music/два.mp3", "/music/три.mp3"),
            events.map { it.path }
        )
        assertEquals(listOf(now - 3 * 60_000, now - 2 * 60_000, now - 60_000), events.map { it.playedAt })

        // Converted on disk, not only in what was returned: the file grew by the header and the
        // numbers, and reading it again gives the same numbers rather than fresh ones.
        assertTrue(historyFile.length() > before, "файл не перезаписан: $before -> ${historyFile.length()}")
        dao.appendPlayEvent(listen("/music/четыре.mp3", now))
        assertEquals(listOf(1L, 2L, 3L, 4L), dao().playEvents().map { it.id })
    }

    @Test
    fun whatCameFromAnotherDeviceIsNeverOfferedForExport() = runBlocking {
        val dao = dao()
        dao.appendPlayEvent(listen("/music/своё.mp3", now - 60_000))
        dao.appendPlayEvents(listOf(
            listen("/music/чужое.mp3", now - 50_000).copy(deviceId = phone),
            listen("/music/ещё чужое.mp3", now - 40_000).copy(deviceId = phone)
        ))

        assertEquals(3, dao.playEvents().size)
        val mine = dao.playEventsToExport(0, 100)
        assertEquals(listOf("/music/своё.mp3"), mine.map { it.path })
        assertEquals(
            listOf("/music/чужое.mp3", "/music/ещё чужое.mp3"),
            dao.playEventsFromOtherDevices(0).map { it.path }
        )
        assertEquals(listOf(phone), dao.playEventsFromOtherDevices(0).map { it.deviceId }.distinct())
    }

    @Test
    fun theOutboxCarriesOnFromItsWatermarkAndTakesNoMoreThanAsked() = runBlocking {
        val dao = dao()
        repeat(5) { dao.appendPlayEvent(listen("/music/$it.mp3", now - (5 - it) * 60_000L)) }

        assertEquals(listOf(1L, 2L), dao.playEventsToExport(0, 2).map { it.id })
        assertEquals(listOf(3L, 4L, 5L), dao.playEventsToExport(2, 100).map { it.id })
        assertEquals(emptyList(), dao.playEventsToExport(5, 100).map { it.id })
    }

    @Test
    fun onlyWhatArrivedSinceIsCountedAsAlreadyHeld() = runBlocking {
        val dao = dao()
        dao.appendPlayEvents(listOf(
            listen("/music/старое.mp3", now - 40 * day).copy(deviceId = phone),
            listen("/music/новое.mp3", now - 60_000).copy(deviceId = phone)
        ))
        assertEquals(
            listOf("/music/новое.mp3"),
            dao.playEventsFromOtherDevices(now - 30 * day).map { it.path }
        )
    }

    @Test
    fun pruningKeepsTheNumbersAndTheDevices() = runBlocking {
        val dao = dao()
        dao.appendPlayEvent(listen("/music/старое.mp3", now - 400 * day))
        dao.appendPlayEvents(listOf(listen("/music/чужое.mp3", now - 60_000).copy(deviceId = phone)))
        dao.appendPlayEvent(listen("/music/новое.mp3", now))

        dao.prunePlayEvents(now - 365 * day)

        val left = dao.playEvents()
        assertEquals(listOf("/music/чужое.mp3", "/music/новое.mp3"), left.map { it.path })
        // The numbers are the ones they were given, not their new positions — the whole point of
        // storing them. A watermark at 2 must still mean "the third record and after".
        assertEquals(listOf(2L, 3L), left.map { it.id })
        assertEquals(listOf(phone, ""), left.map { it.deviceId })
        assertEquals(listOf("/music/новое.mp3"), dao.playEventsToExport(0, 100).map { it.path })

        // And the next listen carries on from the highest number, not from what is left.
        dao.appendPlayEvent(listen("/music/следующее.mp3", now + 60_000))
        assertEquals(4L, dao.newestPlayEventId())
    }

    @Test
    fun anInterruptedAppendCostsOnlyItsOwnRecord() = runBlocking {
        val dao = dao()
        dao.appendPlayEvent(listen("/music/один.mp3", now - 60_000))
        dao.appendPlayEvent(listen("/music/два.mp3", now))
        RandomAccessFile(historyFile, "rw").use { it.setLength(historyFile.length() - 9) }

        val events = dao().playEvents()
        assertEquals(listOf("/music/один.mp3"), events.map { it.path })
        assertEquals(listOf(1L), events.map { it.id })
    }

    @Test
    fun aLogThatCouldNotBeConvertedIsNotAppendedTo() {
        // The two shapes cannot be mixed in one file: a numbered record appended to an unnumbered
        // log would leave something neither reader can make sense of. So a conversion that fails
        // — a read-only directory, a full disk — costs the new listen and not the old ones.
        writeLegacyLog(
            listen("/music/один.mp3", now - 2 * 60_000),
            listen("/music/два.mp3", now - 60_000)
        )
        val before = historyFile.readBytes()
        baseDir.setWritable(false)
        try {
            runBlocking {
                val dao = dao()
                assertEquals(2, dao.playEvents().size)
                dao.appendPlayEvent(listen("/music/три.mp3", now))
            }
        } finally {
            baseDir.setWritable(true)
        }
        assertTrue(before.contentEquals(historyFile.readBytes()), "старый журнал испорчен")

        // And once the directory can be written to again, the conversion happens as it should.
        runBlocking {
            val dao = dao()
            dao.appendPlayEvent(listen("/music/три.mp3", now))
            assertEquals(listOf(1L, 2L, 3L), dao.playEvents().map { it.id })
        }
    }

    @Test
    fun aLogFromANewerVersionIsNotWrittenOver() = runBlocking {
        DataOutputStream(historyFile.outputStream().buffered()).use { out ->
            out.writeInt(0x41494D48)        // the magic this build writes
            out.writeInt(99)                // a record shape it has never heard of
            out.writeLong(1L)
            out.writeUTF("")
            out.writeUTF("/music/из будущего.mp3")
        }
        val before = historyFile.readBytes()

        val dao = dao()
        assertEquals(emptyList(), dao.playEvents())
        dao.appendPlayEvent(listen("/music/сегодняшнее.mp3", now))
        dao.prunePlayEvents(now - 365 * day)

        assertTrue(before.contentEquals(historyFile.readBytes()), "файл будущей версии изменён")
    }
}
