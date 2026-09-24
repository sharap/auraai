package music.ai.recommend.db

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the real database on the device and touches both tables.
 *
 * This exists because a migration once created an index that the entity did not declare: Room
 * compares the whole schema on open, so it refused to open the file at all, and the embeddings —
 * hours of analysis — looked lost until the app was downgraded. Schema mismatches only surface
 * when an existing database is opened, which no unit test can do.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseOpensTest {

    @Test
    fun theDatabaseOpensAndBothTablesAreReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = AppDatabase.getDatabase(context)

        val embeddings = database.embeddingDao().count
        val events = database.playEventDao().count()

        Log.i("DatabaseOpensTest", "embeddings=$embeddings playEvents=$events")
        assertTrue("embeddings: $embeddings", embeddings >= 0)
        assertTrue("play events: $events", events >= 0)
    }
}
