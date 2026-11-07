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
 * The shared decoders on this phone's own library, printing what they got.
 *
 * The fixture tests say the decoders are deterministic, and the desktop's comparisons against
 * ffmpeg say they are right. What neither can say is that *this device* produces the same samples
 * as that one, because both of those run on a desktop JVM — the Android unit tests included. So
 * this runs on the phone, over files the two machines both have, and prints a digest per file for
 * the desktop's own run to be put beside it. Equal digests are the whole promise of a shared
 * decoder: the vector made here is the vector made there.
 *
 * It only reads. Nothing in the app's data is touched, which is why it is safe to run on a phone
 * with a library somebody cares about.
 */
@RunWith(AndroidJUnit4::class)
class SharedDecodersOnTheDeviceTest {

    @Test
    fun everyFormatWithASharedDecoderDecodesToSomethingWorthComparing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val byFormat = HashMap<String, MutableList<String>>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA),
            null, null,
            MediaStore.Audio.Media.DATA + " ASC"
        )?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(column) ?: continue
                val decoder = SharedDecoder.of(path) ?: continue
                val taken = byFormat.getOrPut(decoder) { ArrayList() }
                if (taken.size < HOW_MANY && File(path).isFile) taken.add(path)
            }
        }
        if (byFormat.isEmpty()) {
            println("SharedDecoders: nothing on this phone that a shared decoder handles")
            return
        }

        var decoded = 0
        for ((decoder, paths) in byFormat.toSortedMap()) {
            for (path in paths) {
                val file = File(path)
                val samples = SharedDecoder.decode(file)
                val start = SharedDecoder.startMs(file)
                if (samples.isEmpty()) {
                    println("SharedDecoders: $decoder would not decode ${file.name}")
                    continue
                }
                decoded++
                println(
                    "DECODERSUM $decoder|${file.parentFile?.name}/${file.name}|$start|" +
                        "${samples.size}|${digestOf(samples)}"
                )
            }
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
        /** Per format: enough to be convincing, few enough to run in seconds. */
        const val HOW_MANY = 6
    }
}
