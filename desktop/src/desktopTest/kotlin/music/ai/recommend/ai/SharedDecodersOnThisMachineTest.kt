package music.ai.recommend.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test

/**
 * The same as the phone's `SharedDecodersOnTheDeviceTest`, on this machine's library, printing the
 * same lines so the two can be put side by side.
 *
 * Both devices hold the same music — that is what the sync folder is for — so a file of one name
 * in one album folder is the same bytes on both. Equal digests then say that an embedding made on
 * either is the embedding the other would have made, which is the only thing that justifies
 * carrying one across.
 */
class SharedDecodersOnThisMachineTest {

    private val library = File(System.getProperty("user.home"), "Music")

    @Test
    fun everyFormatWithASharedDecoderDecodesToSomethingWorthComparing() {
        if (!library.isDirectory) {
            println("SharedDecoders: no ${library.path}, nothing to compare")
            return
        }
        val byFormat = HashMap<String, MutableList<File>>()
        library.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            val decoder = SharedDecoder.of(file.name) ?: return@forEach
            val taken = byFormat.getOrPut(decoder) { ArrayList() }
            if (taken.size < HOW_MANY) taken.add(file)
        }
        for ((decoder, files) in byFormat.toSortedMap()) {
            for (file in files) {
                val samples = SharedDecoder.decode(file)
                if (samples.isEmpty()) {
                    println("SharedDecoders: $decoder would not decode ${file.name}")
                    continue
                }
                println(
                    "DECODERSUM $decoder|${file.parentFile?.name}/${file.name}|" +
                        "${SharedDecoder.startMs(file)}|${samples.size}|${digestOf(samples)}"
                )
            }
        }
    }

    private fun digestOf(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Per format, and more than the phone takes, so the phone's are all matched. */
        const val HOW_MANY = 40
    }
}
