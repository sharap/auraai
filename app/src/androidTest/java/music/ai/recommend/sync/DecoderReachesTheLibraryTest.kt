package music.ai.recommend.sync

import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import music.ai.recommend.ai.Mp3Excerpt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Can the shared decoder actually open this library's files?
 *
 * It has to, for two separate jobs: the analysis decodes them, and sync asks the same decoder where
 * each one's excerpt begins. A file the decoder cannot open has no window, so its vector is held
 * back — silently, since being unable to describe a track is a normal outcome for a file that has
 * been deleted. Which makes "most of the library cannot be described" look exactly like "most of
 * the library is gone", and that is worth measuring rather than assuming.
 */
@RunWith(AndroidJUnit4::class)
class DecoderReachesTheLibraryTest {

    @Test
    fun theDecoderCanNameTheWindowForTheLibrarysOwnFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = ArrayList<String>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA),
            "${MediaStore.Audio.Media.DATA} LIKE '%.mp3'",
            null,
            "${MediaStore.Audio.Media._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && paths.size < 200) {
                cursor.getString(0)?.let { paths.add(it) }
            }
        }
        assumeTrue("нет mp3 в фонотеке", paths.isNotEmpty())

        var named = 0
        var missing = 0
        var unopenable = 0
        val examples = StringBuilder()
        for (path in paths) {
            val file = File(path)
            when {
                !file.isFile -> {
                    missing++
                    if (examples.length < 300) examples.append("\n  нет файла: $path")
                }
                Mp3Excerpt.startMs(file) != null -> named++
                else -> {
                    unopenable++
                    if (examples.length < 300) {
                        examples.append("\n  не открылся: $path (${file.length()} байт, ")
                            .append("читается: ${file.canRead()})")
                    }
                }
            }
        }

        Log.i(TAG, "из ${paths.size} mp3: окно названо $named, файла нет $missing, " +
            "не открылось $unopenable$examples")
        assertTrue(
            "окно названо только для $named из ${paths.size}",
            named >= paths.size - missing
        )
    }

    private companion object {
        const val TAG = "DecoderReachesTheLibraryTest"
    }
}
