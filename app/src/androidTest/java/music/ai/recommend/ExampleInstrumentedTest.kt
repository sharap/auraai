package music.ai.recommend

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * That the tests are talking to this app at all.
 *
 * The package name is matched by its start and not in full, because a debug build carries a
 * suffix: `music.ai.recommend.debug`, so that it installs beside the real app instead of replacing
 * it. The suffix may only ever be appended — if this fails, a build is pretending to be some other
 * application, and an instrumented test would then be examining one app while the tester reads the
 * other.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun theTestsAreTalkingToThisApp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(appContext.packageName, appContext.packageName.startsWith("music.ai.recommend"))
    }
}