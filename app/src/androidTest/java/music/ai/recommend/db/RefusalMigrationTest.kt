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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five files in this library that are not music, as the upgrade finds them.
 *
 * Until version 8 a file that would not decode was stored as 512 zeros. Those rows are on this
 * phone and on the desktop right now, and the upgrade has to turn them into refusals without
 * touching the four and a half thousand real vectors beside them — which is the whole risk of the
 * change, since the rows are told apart by their *contents* and nothing else.
 *
 * Unlike [music.ai.recommend.sync.VectorMigrationTest] this needs no second build and does not go
 * near the app's own database: it builds a version 7 file of its own, hands it to Room, and lets
 * the registered migrations run exactly as they would on the real one.
 */
@RunWith(AndroidJUnit4::class)
class RefusalMigrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val name = "refusal-migration-test"

    private val broken = 90_101L
    private val whole = 90_102L
    private val published = 90_103L

    private fun blob(seed: Int) = ByteArray(512 * 4) { ((it + seed) % 251 + 1).toByte() }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    /** The embeddings and play_events tables exactly as version 7 left them. */
    private fun writeVersion7() {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS embeddings (songId INTEGER NOT NULL," +
                                " vector BLOB, publishedAt INTEGER NOT NULL DEFAULT 0," +
                                " PRIMARY KEY(songId))"
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
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, from: Int, to: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO embeddings (songId, vector, publishedAt) VALUES (?, ?, 0)",
                arrayOf<Any>(broken, ByteArray(512 * 4))
            )
            db.execSQL(
                "INSERT INTO embeddings (songId, vector, publishedAt) VALUES (?, ?, 0)",
                arrayOf<Any>(whole, blob(1))
            )
            db.execSQL(
                "INSERT INTO embeddings (songId, vector, publishedAt) VALUES (?, ?, 1700000000000)",
                arrayOf<Any>(published, blob(2))
            )
        }
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @Test
    fun aZeroVectorBecomesARefusalAndTheRealOnesAreUntouched() {
        writeVersion7()
        val database = openMigrated()
        try {
            val dao = database.embeddingDao()

            // The broken file is no longer analysed, and no longer carries a vector at all.
            assertEquals(listOf(whole, published), dao.getAllIds().sorted())
            assertEquals(mapOf(broken to ""), dao.getRefusals().associate { it.songId to it.stamp })
            assertEquals(1, dao.countRefused())
            assertNull(dao.getAll().single { it.songId == broken }.vector)

            // Nothing is lost: three rows in, three rows out, bytes and marks as they were.
            assertEquals(3, dao.getCount())
            val rows = dao.getAll().associateBy { it.songId }
            assertEquals(blob(1).toList(), rows.getValue(whole).vector.toList())
            assertEquals(blob(2).toList(), rows.getValue(published).vector.toList())
            assertEquals(0L, rows.getValue(whole).publishedAt)
            assertEquals(1_700_000_000_000L, rows.getValue(published).publishedAt)
            assertNull(rows.getValue(whole).refusedStamp)

            // And the refusal is kept out of the outbox, however long it has waited there.
            assertEquals(listOf(whole), dao.notPublished(100).map { it.songId })
            assertEquals(1, dao.countNotPublished())
        } finally {
            database.close()
        }
    }

    @Test
    fun aRefusalStoredNowSurvivesBeingReopened() {
        writeVersion7()
        var database = openMigrated()
        try {
            val entity = EmbeddingEntity(whole, null)
            entity.refusedStamp = "2048:1700000000000"
            database.embeddingDao().insert(entity)
        } finally {
            database.close()
        }
        database = openMigrated()
        try {
            val dao = database.embeddingDao()
            assertEquals(
                mapOf(broken to "", whole to "2048:1700000000000"),
                dao.getRefusals().associate { it.songId to it.stamp }
            )
            assertTrue(whole !in dao.getAllIds())
        } finally {
            database.close()
        }
    }
}
