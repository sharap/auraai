package music.ai.recommend.sync

import music.ai.recommend.ai.Mp3Excerpt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terms on which somebody else's embedding may be believed.
 *
 * Every refusal here is cheap — the track is analysed locally instead, which is what would have
 * happened anyway — and every wrong acceptance is expensive and silent: a track sitting in the
 * wrong place in the similarity space, with every answer drawn from it quietly off. So the tests
 * are mostly about refusing.
 */
class VectorTermsTest {

    private val fp32 = "3ecc72d27740e2a09ced20cf22fd6244122e5e506008763a0f368b3b4ff6eac8"
    private val int8 = "021dd4fb962b4ed20cc3a6730b09e0ccf9f9c49931047032118a3b64828513a6"

    private val terms = VectorTerms(model = fp32, analysisVersion = 3, dimensions = 512)

    private val vector = FloatArray(512) { (it % 7) * 0.1f + 0.01f }

    private fun record(
        decoder: String = Mp3Excerpt.DECODER,
        model: String = fp32,
        version: Int = 3,
        start: Int = 100_000,
        floats: FloatArray = vector
    ) = VectorRecord(
        track = TrackKey(null, 210_000, "мельница", "ночная кобыла"),
        excerptStartMs = start,
        decoder = decoder,
        model = model,
        analysisVersion = version,
        vector = floats
    )

    private fun judge(record: VectorRecord, localDecoder: String = Mp3Excerpt.DECODER, start: Int = 100_000) =
        terms.judge(record, localDecoder, start)

    @Test
    fun anMp3VectorFromAnIdenticalPipelineIsUsable() {
        assertEquals(VectorVerdict.Usable, judge(record()))
    }

    @Test
    fun theOtherExportOfTheSameNetworkIsRefused() {
        // int8 and fp32 do not produce the same numbers, and this is the likely case in practice:
        // a phone defaults to the quantised model, the desktop has only the full one.
        assertEquals(VectorVerdict.OtherModel, judge(record(model = int8)))
    }

    @Test
    fun anEmbeddingFromBeforeSomethingChangedIsRefused() {
        assertEquals(VectorVerdict.OtherVersion, judge(record(version = 2)))
        assertEquals(VectorVerdict.OtherVersion, judge(record(version = 4)))
    }

    @Test
    fun aVectorForAFileTheTwoSidesWouldDecodeDifferentlyIsRefused() {
        // The sender had an mp3; this device's copy of the same recording is an m4a, so its own
        // samples would come from somewhere else entirely.
        assertEquals(VectorVerdict.OtherDecoder, judge(record(), localDecoder = "mediacodec-android"))
    }

    @Test
    fun aPlatformDecoderIsRefusedEvenWhenBothSidesNameTheSameOne() {
        // Two phones, both MediaCodec. The names agree and the behaviour need not: that is the
        // vendor's to change, and it is exactly the case this rule exists for. Only a decoder that
        // is the same code in both apps may be believed.
        assertEquals(
            VectorVerdict.DecoderDoesNotTravel,
            judge(record(decoder = "mediacodec-android"), localDecoder = "mediacodec-android")
        )
        assertTrue(Mp3Excerpt.DECODER in VectorTerms.TRAVELS)
        assertEquals(1, VectorTerms.TRAVELS.size)
    }

    @Test
    fun aDisagreementAboutWhichTenSecondsIsRefusedRatherThanPaperedOver() {
        // Twenty-one milliseconds apart costs a cosine of 0.9585 where 0.9985 is the bar, so this
        // is not a rounding difference to shrug at. One millisecond is enough to refuse.
        assertEquals(VectorVerdict.OtherExcerpt, judge(record(start = 100_001)))
        assertEquals(VectorVerdict.OtherExcerpt, judge(record(start = 99_000)))
        assertEquals(VectorVerdict.Usable, judge(record(start = 100_000)))
    }

    @Test
    fun theWrongNumberOfFloatsIsRefused() {
        assertEquals(VectorVerdict.WrongShape, judge(record(floats = FloatArray(511) { 0.1f })))
        assertEquals(VectorVerdict.WrongShape, judge(record(floats = FloatArray(513) { 0.1f })))
    }

    @Test
    fun theZeroVectorIsNotAnEmbedding() {
        // Both apps store all zeros for a file they could not decode, so the scan does not keep
        // retrying it. That is a note to self and not a description of any music — and the device
        // receiving it may well be able to decode the file.
        assertEquals(VectorVerdict.Empty, judge(record(floats = FloatArray(512))))
    }
}
