package music.ai.recommend

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the view model can be built at all, which is to say that the app opens.
 *
 * Trivial-looking and worth having, because the one time it stopped being true the app would not
 * start and nothing in the build said so. Kotlin initialises properties in declaration order and
 * runs `init` among them, so a flag declared below the init block that reads it is null when it is
 * read — a null state flow, a NullPointerException inside the constructor, and
 * "Cannot create an instance of class MusicViewModel" on a blank screen. No unit test can see it:
 * it needs a real Application, and it is exactly what this is.
 */
@RunWith(AndroidJUnit4::class)
class MusicViewModelStartsTest {

    @Test
    fun theViewModelCanBeBuilt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        var built: MusicViewModel? = null
        var failure: Throwable? = null
        // On the main thread, as the framework would: the constructor starts coroutines and binds
        // to the playback service.
        instrumentation.runOnMainSync {
            try {
                built = MusicViewModel(application)
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw AssertionError("не удалось построить модель представления", it) }
        assertNotNull(built)

        // And every flow the settings screen reads is there to be read.
        val model = built!!
        assertNotNull(model.syncSummary.value)
        assertNotNull(model.isPlaying.value)
        instrumentation.runOnMainSync { model.viewModelStore() }
    }

    /** Releases what the constructor took, so the test leaves nothing running. */
    private fun MusicViewModel.viewModelStore() {
        androidx.lifecycle.ViewModelStore().also { store ->
            store.put("test", this)
            store.clear()
        }
    }
}
