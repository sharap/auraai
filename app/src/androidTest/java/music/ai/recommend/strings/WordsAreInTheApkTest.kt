package music.ai.recommend.strings

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import music.ai.recommend.ai.AlbumLabels
import music.ai.recommend.ai.WaveMoods
import music.ai.recommend.strings.resources.Res
import music.ai.recommend.strings.resources.allStringResources
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the words this app shows are actually inside the APK.
 *
 * Written after they were not. The strings both interfaces show live in `:strings` and are read
 * through Compose Resources, which on Android means files under `assets/composeResources/…`. AGP's
 * KMP library plugin gives that module's android variant no `sources.assets`, so the Compose
 * plugin's own copy task is left unconfigured and the AAR goes out with no words in it — and
 * nothing anywhere says so. The desktop reads the same words out of its jar and works, every unit
 * test passes, the APK builds, installs and runs; then the playlists screen asks for the name of a
 * wave mood and the app dies with "Missing resource with path:
 * composeResources/music.ai.recommend.strings.resources/values/strings.commonMain.cvr".
 *
 * It is an instrumented test because that is the only place the question can be asked: a unit test
 * on the JVM reads the words off the classpath, where they always are, and would have passed
 * throughout.
 */
@RunWith(AndroidJUnit4::class)
class WordsAreInTheApkTest {

    @Test
    fun everyWaveMoodHasItsName() = runBlocking {
        for (mood in WaveMoods.all) {
            val resource = Res.allStringResources[mood.nameKey]
            assertNotNull("no string resource for ${mood.nameKey}", resource)
            val name = getString(resource!!)
            assertTrue("${mood.nameKey} came back empty", name.isNotBlank())
            // The prompt is English and several words long; the name is one or two. A name as long
            // as its prompt means the caller's fallback was used, which is what a missing resource
            // looks like from the outside.
            assertTrue("${mood.nameKey} is not a name but a prompt", name.length < mood.prompt.length)
        }
    }

    @Test
    fun everyAlbumLabelHasItsName() = runBlocking {
        for (label in AlbumLabels.all) {
            val resource = Res.allStringResources[label.nameKey]
            assertNotNull("no string resource for ${label.nameKey}", resource)
            assertTrue("${label.nameKey} came back empty", getString(resource!!).isNotBlank())
        }
    }
}
