package music.ai.recommend.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import music.ai.recommend.db.AppDatabase
import music.ai.recommend.db.PlayEventEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The listening history has to survive gaining a column, on a database that was made by the
 * version before.
 *
 * Which cannot be checked in one run, so it is two tests meant to be used in order and by hand:
 *
 *   1. with the *previous* build installed, run `seedTheOldDatabase` — it writes rows through raw
 *      SQL, naming only the columns that version has, so the same source compiles either side of
 *      the change;
 *   2. install the build under test, which migrates the file as Room opens it, and run
 *      `theRowsSurviveAndBelongToThisDevice`.
 *
 * Worth the trouble because of what is at stake. Analysis can be redone in an evening; months of
 * listening cannot be redone at all, and a migration that drops it would be noticed as the wave
 * quietly forgetting what the owner likes.
 */
@RunWith(AndroidJUnit4::class)
class HistoryMigrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val seeded = listOf(
        Triple(1001L, 1_799_990_000_000L, 180_000L),
        Triple(1002L, 1_799_991_000_000L, 4_000L),
        Triple(1003L, 1_799_992_000_000L, 240_000L)
    )

    @Test
    fun seedTheOldDatabase() {
        val database = AppDatabase.getDatabase(context)
        val writable = database.openHelper.writableDatabase
        writable.execSQL("DELETE FROM play_events")
        for ((songId, playedAt, playedMs) in seeded) {
            writable.execSQL(
                "INSERT INTO play_events (songId, playedAt, playedMs, durationMs) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(songId, playedAt, playedMs, 240_000L)
            )
        }
        assertEquals(seeded.size, database.playEventDao().count())
    }

    @Test
    fun theRowsSurviveAndBelongToThisDevice() {
        val dao = AppDatabase.getDatabase(context).playEventDao()
        val rows = dao.since(0).sortedBy { it.playedAt }
        assertEquals(seeded.size, rows.size)
        assertEquals(seeded.map { it.first }, rows.map { it.songId })
        assertEquals(seeded.map { it.third }, rows.map { it.playedMs })
        // The default says what every row already there was: heard here.
        assertEquals(listOf("", "", ""), rows.map { it.deviceId })

        // All three are this device's, so all three are waiting to be exported.
        assertEquals(seeded.size, dao.notYetExported(0, 100).size)
        assertEquals(rows.last().id, dao.newestId())
        assertEquals(0, dao.fromOtherDevicesSince(0).size)
    }

    @Test
    fun aListenFromElsewhereIsKeptApartFromOurOwn() {
        val dao = AppDatabase.getDatabase(context).playEventDao()
        val other = "22".repeat(16)
        dao.insert(PlayEventEntity(2001L, 1_799_993_000_000L, 200_000L, 210_000L, other))
        try {
            // It counts towards what the wave and the daily mix see.
            assertEquals(seeded.size + 1, dao.since(0).size)
            assertEquals(1, dao.fromOtherDevicesSince(0).size)
            assertEquals(other, dao.fromOtherDevicesSince(0).single().deviceId)
            // And it must never go back out under this device's name.
            assertEquals(seeded.size, dao.notYetExported(0, 100).size)
            assertEquals(listOf(""), dao.notYetExported(0, 100).map { it.deviceId }.distinct())
        } finally {
            // Precisely that one row: deleting by age would take the seed with it.
            dao.deleteFromDevice(other)
        }
    }
}
