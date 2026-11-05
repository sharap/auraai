package music.ai.recommend.sync

import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import music.ai.recommend.ai.Excerpt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measurement, not a verdict: do the two durations Android offers put the excerpt in the same place?
 *
 * The analysis takes the duration from the container, through MediaExtractor. Everything else in the
 * app — including the key a track travels under — takes it from MediaStore. If those two ever
 * disagree enough to cross one of `Excerpt.startMs`'s two-second buckets, then a vector published
 * with a start worked out from the MediaStore reading would name a window the vector was not made
 * from, and the receiving device would have no way to tell.
 *
 * Measured over 400 mp3s of this library: the two agree exactly for 182 of them, differ by at most
 * one millisecond for the rest, and land in the same bucket for all 400. Which is not good enough,
 * and is why `VectorStore.excerptStartMs` asks the platform instead of working it out from the key:
 * one millisecond of difference crosses a bucket boundary for about one track in two thousand, so a
 * library this size has a couple of them, and for those a vector would be believed on terms that
 * describe a different ten seconds. What this test still guards is that the two readings remain the
 * same measurement rather than two different ones.
 */
@RunWith(AndroidJUnit4::class)
class DurationAgreementTest {

    @Test
    fun howOftenTheTwoReadingsDisagree() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION),
            "${MediaStore.Audio.Media.DATA} LIKE '%.mp3'",
            null,
            null
        ) ?: error("no cursor")

        var looked = 0
        var readable = 0
        var sameMs = 0
        var sameStart = 0
        var worstMs = 0L
        val examples = StringBuilder()

        cursor.use {
            val dataAt = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationAt = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (it.moveToNext() && looked < 400) {
                looked++
                val path = it.getString(dataAt) ?: continue
                val fromStore = it.getLong(durationAt)
                val fromContainer = containerDurationMs(path) ?: continue
                readable++
                if (fromStore == fromContainer) sameMs++
                val startStore = Excerpt.startMs(fromStore)
                val startContainer = Excerpt.startMs(fromContainer)
                if (startStore == startContainer) {
                    sameStart++
                } else if (examples.length < 500) {
                    examples.append("\n  $path: store=$fromStore→$startStore ")
                        .append("container=$fromContainer→$startContainer")
                }
                val apart = kotlin.math.abs(fromStore - fromContainer)
                if (apart > worstMs) worstMs = apart
            }
        }

        val summary = "looked at $looked mp3s, $readable readable; identical ms: $sameMs; " +
            "same excerpt: $sameStart; furthest apart: $worstMs ms$examples"
        Log.i(TAG, summary)

        assumeTrue("нет mp3, нечего измерять", readable > 0)
        // Not "they always agree" — they do not, and the code is built for that. What would be a
        // different world is the two becoming different measurements rather than two roundings of
        // one: a MediaStore that estimated a VBR length while the container stated it would be
        // seconds apart, not a millisecond, and would deserve looking at again.
        assertTrue("$worstMs мс между двумя чтениями длительности", worstMs <= 50)
    }

    private fun containerDurationMs(path: String): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                if (!format.containsKey(MediaFormat.KEY_DURATION)) return null
                return format.getLong(MediaFormat.KEY_DURATION) / 1000
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TAG = "DurationAgreementTest"
    }
}
