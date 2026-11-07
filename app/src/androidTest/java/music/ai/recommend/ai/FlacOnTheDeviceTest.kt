package music.ai.recommend.ai

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The shared flac decoder on this phone's own library, printing what it got.
 *
 * The fixture test says the decoder is deterministic; `FlacAgainstFfmpegTest` on the desktop says
 * it is correct. What neither can say is that *this device* produces the same samples as that one,
 * because both of those run on a desktop JVM — the Android unit tests included. So this runs on
 * the phone, over files the two machines both have, and prints a digest per file for the desktop's
 * own run to be compared against. Equal digests are the whole promise of a shared decoder: the
 * vector made here is the vector made there.
 *
 * It only reads. Nothing in the app's data is touched, which is why it is safe to run on a phone
 * with a library somebody cares about.
 */
@RunWith(AndroidJUnit4::class)
class FlacOnTheDeviceTest {

    @Test
    fun theFlacsOnThisPhoneDecodeToSomethingWorthComparing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = ArrayList<String>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA),
            null, null,
            MediaStore.Audio.Media.DATA + " ASC"
        )?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext() && paths.size < HOW_MANY) {
                val path = cursor.getString(column) ?: continue
                if (FlacExcerpt.handles(path) && File(path).isFile) paths.add(path)
            }
        }
        if (paths.isEmpty()) {
            println("FlacOnTheDevice: no flac files on this phone, nothing to compare")
            return
        }

        var decoded = 0
        for (path in paths) {
            val file = File(path)
            val samples = FlacExcerpt.decode(file)
            val start = FlacExcerpt.startMs(file)
            if (samples.isEmpty()) {
                println("FlacOnTheDevice: ${file.name} would not decode")
                continue
            }
            decoded++
            println("FLACSUM ${file.parentFile?.name}/${file.name}|$start|${samples.size}|${digestOf(samples)}")
        }
        assertTrue("ни один файл не декодировался", decoded > 0)
    }

    private fun digestOf(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Enough to be convincing, few enough to run in seconds over a phone's storage. */
        const val HOW_MANY = 8
    }
}
