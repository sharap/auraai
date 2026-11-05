package music.ai.recommend.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import music.ai.recommend.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The embeddings table has to survive gaining the column that says whether a vector has been
 * offered to the other devices, on a database made by the version before.
 *
 * Two tests meant to be used in order and by hand, like [HistoryMigrationTest]:
 *
 *   1. with the *previous* build installed, run `seedTheOldTable` — it writes rows through raw SQL
 *      naming only the columns that version has, so the same source compiles either side;
 *   2. install the build under test, which migrates the file as Room opens it, and run
 *      `theVectorsSurviveAndAreNotYetPublished`.
 *
 * Less is at stake here than with history — analysis can be redone in an evening — but a migration
 * that fails does not lose vectors, it stops the app from opening at all, and the default on the new
 * column is also a statement about meaning: every vector already in the table was made before there
 * was anywhere to send it, so none of them has been offered.
 */
@RunWith(AndroidJUnit4::class)
class VectorMigrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val seeded = listOf(90_001L, 90_002L, 90_003L)

    private fun blob(seed: Int) = ByteArray(512 * 4) { ((it + seed) % 251).toByte() }

    @Test
    fun seedTheOldTable() {
        val database = AppDatabase.getDatabase(context)
        val writable = database.openHelper.writableDatabase
        for ((index, songId) in seeded.withIndex()) {
            writable.execSQL(
                "INSERT OR REPLACE INTO embeddings (songId, vector) VALUES (?, ?)",
                arrayOf<Any>(songId, blob(index))
            )
        }
        val count = writable.query("SELECT COUNT(*) FROM embeddings WHERE songId >= 90000").use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(seeded.size, count)
    }

    @Test
    fun theVectorsSurviveAndAreNotYetPublished() {
        val dao = AppDatabase.getDatabase(context).embeddingDao()
        val rows = dao.getAll().filter { it.songId >= 90_000L }.sortedBy { it.songId }
        assertEquals(seeded, rows.map { it.songId })
        for ((index, row) in rows.withIndex()) {
            assertEquals("вектор ${row.songId} изменился", blob(index).toList(), row.vector.toList())
            // The default says what every row already there is: made before there was anywhere to
            // send it, so never offered.
            assertEquals(0L, row.publishedAt)
        }
        // And they are exactly what the outbox would pick up first.
        val waiting = dao.notPublished(1000).map { it.songId }
        assertEquals(seeded, waiting.filter { it >= 90_000L })
    }

    /**
     * Hand-run: offers every vector this device holds to the others again.
     *
     * The same statement the 6→7 migration runs, kept as a hatch because it is the one repair this
     * feature needs and there is no other way in: a folder is a cache a device can be restored
     * from only if every device offers what it holds, and a marked row is never offered again.
     * Useful after anything that leaves the marks saying more than the folder does.
     */
    @Test
    fun offerEverythingAgain() {
        val database = AppDatabase.getDatabase(context)
        database.openHelper.writableDatabase.execSQL("UPDATE embeddings SET publishedAt = 0")
        val waiting = database.embeddingDao().countNotPublished()
        assertEquals(database.embeddingDao().getCount(), waiting)
    }

    @Test
    fun removeTheSeededRows() {
        AppDatabase.getDatabase(context).openHelper.writableDatabase
            .execSQL("DELETE FROM embeddings WHERE songId >= 90000")
    }
}
