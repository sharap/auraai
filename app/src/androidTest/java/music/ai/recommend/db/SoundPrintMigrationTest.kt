package music.ai.recommend.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What happens to the prints already on a phone when the table grows a column.
 *
 * Worth a test of its own because of what those rows cost and how quietly they could go: a print
 * is a seek and a quarter of a megabyte per file, the library this was written against holds four
 * and a half thousand of them, and a dropped column would show up only as the phone hashing its
 * whole library again one afternoon with nothing in the log to say why.
 *
 * Built by hand rather than exported, for the same reason as [RefusalMigrationTest]: the test
 * writes a version 9 file of its own, hands it to Room, and lets the app's own migration list do
 * the rest.
 */
@RunWith(AndroidJUnit4::class)
class SoundPrintMigrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val name = "sound-print-migration-test"

    private val hashed = 91_201L
    private val replaced = 91_202L

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    /** The tables exactly as version 9 left them: sound_prints with no excerpt start. */
    private fun writeVersion9() {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS embeddings (songId INTEGER NOT NULL," +
                                " vector BLOB, publishedAt INTEGER NOT NULL DEFAULT 0," +
                                " refusedStamp TEXT, PRIMARY KEY(songId))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS play_events (id INTEGER PRIMARY KEY" +
                                " AUTOINCREMENT NOT NULL, songId INTEGER NOT NULL," +
                                " playedAt INTEGER NOT NULL, playedMs INTEGER NOT NULL," +
                                " durationMs INTEGER NOT NULL," +
                                " deviceId TEXT NOT NULL DEFAULT '')"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_play_events_playedAt" +
                                " ON play_events (playedAt)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS sound_prints (" +
                                "songId INTEGER NOT NULL PRIMARY KEY," +
                                " stamp TEXT," +
                                " print TEXT)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, from: Int, to: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO sound_prints (songId, stamp, print) VALUES (?, ?, ?)",
                arrayOf<Any>(hashed, "4096:1700000000000", "aa".repeat(32))
            )
            db.execSQL(
                "INSERT INTO sound_prints (songId, stamp, print) VALUES (?, ?, ?)",
                arrayOf<Any>(replaced, "8192:1700000001000", "bb".repeat(32))
            )
        }
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @Test
    fun printsSurviveTheColumnAndStartOutUnmeasured() {
        writeVersion9()
        val database = openMigrated()
        try {
            val rows = database.soundPrintDao().getAll().associateBy { it.songId }

            assertEquals(2, rows.size)
            assertEquals("aa".repeat(32), rows.getValue(hashed).print)
            assertEquals("4096:1700000000000", rows.getValue(hashed).stamp)
            assertEquals("bb".repeat(32), rows.getValue(replaced).print)

            // Null and not zero: nobody has worked these out, and zero would mean they had been
            // worked out and come to zero, which is a real answer for a very short recording.
            assertNull(rows.getValue(hashed).excerptStartMs)
            assertNull(rows.getValue(replaced).excerptStartMs)
        } finally {
            database.close()
        }
    }

    @Test
    fun anExcerptStartCanBeWrittenAndReadBack() {
        writeVersion9()
        val database = openMigrated()
        try {
            val dao = database.soundPrintDao()
            dao.insertAll(
                listOf(
                    SoundPrintEntity(hashed, "4096:1700000000000", "aa".repeat(32), 94_000L),
                    // Zero, which has to survive as zero and not become "unmeasured".
                    SoundPrintEntity(replaced, "8192:1700000001000", "bb".repeat(32), 0L)
                )
            )

            val rows = dao.getAll().associateBy { it.songId }
            assertEquals(94_000L, rows.getValue(hashed).excerptStartMs)
            assertEquals(0L, rows.getValue(replaced).excerptStartMs)
            assertEquals("bb".repeat(32), rows.getValue(replaced).print)
        } finally {
            database.close()
        }
    }
}
