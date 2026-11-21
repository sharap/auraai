package music.ai.recommend.platform

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two doors into CLAP's text space, on a machine that has both models.
 *
 * Skipped rather than passed where the weights are absent — they are 159 MB and not in the
 * repository — because a test that reports success without having looked is worse than one that
 * says it did not run.
 *
 * What it actually checks is the thing a graph signature cannot: that the Russian student answers
 * in the *same* space as CLAP's English tower. Its outputs are 512 numbers whatever happens; the
 * question is whether "спокойное пианино" comes out nearer to "calm piano" than to "drum and bass".
 * If the vocabulary, the input names or the model were wrong, the vector would still be a vector.
 */
class RuTextEncoderOnThisMachineTest {

    private val models = File(System.getProperty("user.home"), ".aimusic/models")

    @Test
    fun `a russian query lands next to its english twin, not next to anything else`() = runBlocking {
        assumeTrue("the Russian encoder is on this machine", File(models, RU_MODEL).isFile)
        assumeTrue("CLAP's text tower is on this machine", File(models, TEXT_MODEL).isFile)

        val encoder = ClapTextEncoder()
        try {
            val russian = encoder.encode("спокойное пианино")
            assertNotNull(russian, "the Russian encoder returned nothing")
            assertEquals(512, russian.size, "the student has to answer in CLAP's dimensions")
            assertTrue(abs(length(russian) - 1f) < 1e-3f, "vectors are unit length: ${length(russian)}")

            val twin = assertNotNull(encoder.encode("calm piano"))
            val other = assertNotNull(encoder.encode("drum and bass"))
            val near = cosine(russian, twin)
            val far = cosine(russian, other)
            assertTrue(
                near > far,
                "«спокойное пианино» should sit nearer to «calm piano» ($near) than to " +
                    "«drum and bass» ($far)"
            )
        } finally {
            encoder.release()
        }
    }

    /** A query with no Cyrillic must not be routed to the student; CLAP answers it itself. */
    @Test
    fun `an english query still goes through CLAP`() = runBlocking {
        assumeTrue("CLAP's text tower is on this machine", File(models, TEXT_MODEL).isFile)
        val encoder = ClapTextEncoder()
        try {
            assertEquals(false, encoder.needsDownload("calm piano"))
            val vector = assertNotNull(encoder.encode("calm piano"))
            assertEquals(512, vector.size)
        } finally {
            encoder.release()
        }
    }

    private fun length(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return kotlin.math.sqrt(sum)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot / (length(a) * length(b))
    }

    private companion object {
        const val RU_MODEL = "ru_text_encoder_quantized.onnx"
        const val TEXT_MODEL = "text_model.onnx"
    }
}
