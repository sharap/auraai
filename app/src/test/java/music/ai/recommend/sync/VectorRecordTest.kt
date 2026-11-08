package music.ai.recommend.sync

import com.google.gson.JsonParser
import music.ai.recommend.ai.Mp3Excerpt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The embedding record on the wire.
 *
 * It is 512 floats and the terms on which they may be believed, and the floats are the part worth
 * testing hardest: they go through base64 written by hand here — neither platform has one both can
 * use at this app's minimum API — and a vector that comes back slightly wrong is a track quietly
 * sitting in the wrong place in the similarity space.
 */
class VectorRecordTest {

    private val model = "3ecc72d27740e2a09ced20cf22fd6244122e5e506008763a0f368b3b4ff6eac8"

    private fun key(artist: String = "мельница", title: String = "ночная кобыла", duration: Long = 210_000) =
        TrackKey(hash = null, durationMs = duration, artist = artist, title = title)

    private fun record(vector: FloatArray, start: Long = 100_000L) = VectorRecord(
        track = key(),
        excerptStartMs = start,
        decoder = Mp3Excerpt.DECODER,
        model = model,
        analysisVersion = 3,
        vector = vector
    )

    private fun clapSized(seed: Int = 7): FloatArray {
        val random = Random(seed)
        return FloatArray(512) { random.nextFloat() * 2 - 1 }
    }

    @Test
    fun aRecordSurvivesTheRoundTripExactly() {
        val original = record(clapSized())
        val back = VectorRecord.fromJson(JsonParser.parseString(original.toJson().toString()).asJsonObject)
        assertNotNull(back)
        // Every float bit for bit, not merely close: the whole point of carrying a vector is that
        // it is the same vector the other device would have computed.
        assertTrue("векторы разошлись", original.vector.contentEquals(back!!.vector))
        assertEquals(original, back)
    }

    @Test
    fun theNumbersThatBreakFloatingPointSurviveToo() {
        val awkward = floatArrayOf(
            0f, -0f, 1f, -1f, Float.MIN_VALUE, Float.MAX_VALUE,
            1e-38f, -1e-38f, 0.1f, 1f / 3f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        )
        val back = VectorRecord.decodeFloats(VectorRecord.encodeFloats(awkward))
        assertNotNull(back)
        for ((index, value) in awkward.withIndex()) {
            assertEquals("элемент $index", value.toRawBits(), back!![index].toRawBits())
        }
        // Negative zero is the one that a comparison would wave through and a bit pattern catches.
        assertEquals((-0f).toRawBits(), back!![1].toRawBits())
    }

    @Test
    fun theBase64IsRealBase64() {
        // Checked against the platform's own rather than against itself, since this one exists
        // only because the platforms' are out of reach: java.util.Base64 arrived in API 26 against
        // this app's 23. Tests run on a JVM, so here it can be used as the reference.
        for (count in 0..8) {
            val floats = FloatArray(count) { Float.fromBits(it * 0x01020304 + 1) }
            val bytes = ByteArray(count * 4)
            for (index in 0 until count) {
                val bits = floats[index].toRawBits()
                bytes[index * 4] = (bits and 0xFF).toByte()
                bytes[index * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
                bytes[index * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
                bytes[index * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
            }
            assertEquals(
                "$count чисел",
                java.util.Base64.getEncoder().encodeToString(bytes),
                VectorRecord.encodeFloats(floats)
            )
        }
    }

    @Test
    fun aVectorOfTheRealSizeIsAboutAsBigAsItShouldBe() {
        // 512 fp32 is 2048 bytes, so the base64 is 2732 characters with its padding. The whole
        // reason this is not a JSON array of numbers is that a library is four thousand of these.
        val line = record(clapSized()).toJson().toString()
        assertEquals(2732, VectorRecord.encodeFloats(clapSized()).length)
        assertTrue("строка $line", line.length in 2800..3000)
    }

    @Test
    fun aVectorOfNothingButZerosIsNotAVector() {
        // One device storing 512 zeros for a file it could not read used to be how a refusal was
        // remembered, and those lines are in the folder to this day. Taken in, they would mark the
        // track analysed on a device that can read the file perfectly well, and it would never
        // look at it again.
        val zeros = VectorRecord(
            track = key(),
            excerptStartMs = 100_000L,
            decoder = Mp3Excerpt.DECODER,
            model = model,
            analysisVersion = 3,
            vector = FloatArray(512)
        )
        val line = JsonParser.parseString(zeros.toJson().toString()).asJsonObject
        assertNull(VectorRecord.fromJson(line))

        // One number away from nothing is a vector again: the rule must not reach any further
        // than it was meant to.
        val nearlyZero = FloatArray(512).also { it[511] = Float.MIN_VALUE }
        assertNotNull(VectorRecord.fromJson(
            JsonParser.parseString(record(nearlyZero).toJson().toString()).asJsonObject
        ))
    }

    @Test
    fun aLineFromSomewhereElseIsDroppedRatherThanGuessedAt() {
        for (broken in listOf(
            """{"s":100000,"dec":"jlayer-1.0.1","m":"m","av":3,"f":"AAAAAA=="}""",            // no key
            """{"k":{"d":1},"s":1,"dec":"jlayer-1.0.1","m":"m","av":3,"f":"AAAAAA=="}""",     // nameless
            """{"k":{"d":1,"n":"x"},"dec":"jlayer-1.0.1","m":"m","av":3,"f":"AAAAAA=="}""",   // no excerpt
            """{"k":{"d":1,"n":"x"},"s":1,"m":"m","av":3,"f":"AAAAAA=="}""",                  // no decoder
            """{"k":{"d":1,"n":"x"},"s":1,"dec":"d","av":3,"f":"AAAAAA=="}""",                // no model
            """{"k":{"d":1,"n":"x"},"s":1,"dec":"d","m":"m","f":"AAAAAA=="}""",               // no version
            """{"k":{"d":1,"n":"x"},"s":1,"dec":"d","m":"m","av":3}""",                       // no floats
            """{"k":{"d":1,"n":"x"},"s":1,"dec":"d","m":"m","av":3,"f":"AAAAA"}""",           // not base64
            """{"k":{"d":1,"n":"x"},"s":1,"dec":"d","m":"m","av":3,"f":"AAAA"}""",            // three bytes
            """{"k":{"d":1,"n":"x"},"s":1,"dec":"d","m":"m","av":3,"f":"!!!!"}""",            // not base64
            """{"k":{"d":1,"n":"x"},"s":1,"dec":"d","m":"m","av":3,"f":""}""",                // no floats
            """{"k":{"d":1,"n":"x"},"s":"soon","dec":"d","m":"m","av":3,"f":"AAAAAA=="}"""    // a word
        )) {
            assertNull(broken, VectorRecord.fromJson(JsonParser.parseString(broken).asJsonObject))
        }
    }

    @Test
    fun aHashIsCarriedWhenThereIsOneAndLeftOutWhenThereIsNot() {
        val hash = "ab".repeat(32)
        val withHash = VectorRecord(
            track = TrackKey(hash, 210_000, "мельница", "ночная кобыла"),
            excerptStartMs = 100_000L,
            decoder = Mp3Excerpt.DECODER,
            model = model,
            analysisVersion = 3,
            vector = floatArrayOf(1f, 2f)
        )
        assertEquals(hash, VectorRecord.fromJson(withHash.toJson())!!.track.hash)

        val withoutHash = record(floatArrayOf(1f))
        assertNull(VectorRecord.fromJson(withoutHash.toJson())!!.track.hash)
        // And the field is left out rather than written as null: a hash is absent in most of this
        // collection, and four thousand nulls are four thousand nulls.
        assertTrue(withoutHash.toJson().toString(), !withoutHash.toJson().toString().contains("\"h\""))
    }

    @Test
    fun equalityComparesTheNumbersAndNotTheArray() {
        // A data class would have compared the FloatArray by identity, and every test here would
        // have been comparing something other than what it says.
        assertEquals(record(clapSized(1)), record(clapSized(1)))
        assertEquals(record(clapSized(1)).hashCode(), record(clapSized(1)).hashCode())
        assertTrue(record(clapSized(1)) != record(clapSized(2)))
        assertTrue(record(clapSized(1), start = 1L) != record(clapSized(1), start = 2L))
    }
}
