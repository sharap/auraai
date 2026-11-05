package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Normalisation decides whether a listen from the other device lands on the right track or on no
 * track at all, so it has to give the same answer on both of them down to the character. These are
 * the cases that actually occur in a collection: two spellings of the same accented letter, the
 * three dashes, the non-breaking space a tag editor leaves behind, and the versions in brackets
 * that must *not* be folded together.
 */
class TrackKeyTest {

    private fun key(artist: String, title: String, durationMs: Long = 200_000, hash: String? = null) =
        TrackKey(hash, durationMs, TrackKey.normalise(artist), TrackKey.normalise(title))

    @Test
    fun caseAndSpacingAreNoise() {
        assertEquals("imagine dragons", TrackKey.normalise("  Imagine   Dragons "))
        assertEquals("believer", TrackKey.normalise("BELIEVER"))
        assertEquals("", TrackKey.normalise("   "))
        assertEquals("", TrackKey.normalise(""))
    }

    @Test
    fun theThreeDashesAreOneDash() {
        assertEquals("nu-virgos", TrackKey.normalise("Nu–Virgos"))
        assertEquals("nu-virgos", TrackKey.normalise("Nu—Virgos"))
        assertEquals("nu-virgos", TrackKey.normalise("Nu-Virgos"))
    }

    @Test
    fun theSpacesThatDoNotLookLikeSpacesAreSpaces() {
        assertEquals("secret garden", TrackKey.normalise("Secret Garden"))
        assertEquals("secret garden", TrackKey.normalise("Secret Garden"))
        assertEquals("secret garden", TrackKey.normalise("Secret\tGarden"))
    }

    @Test
    fun oneSpellingOfEachLetter() {
        // The same word with the accent composed into the letter and written after it.
        assertEquals(TrackKey.normalise("Björk"), TrackKey.normalise("Björk"))
        assertEquals(TrackKey.normalise("ёлка"), TrackKey.normalise("ёлка"))
    }

    @Test
    fun whatIsInBracketsIsNotNoise() {
        val remix = key("Нервы", "Слайдер (Remix)")
        val original = key("Нервы", "Слайдер")
        assertFalse(remix.matches(original))
    }

    @Test
    fun aHashSettlesItWhateverTheTagsSay() {
        val sameFile = "ab".repeat(32)
        val tagged = key("Imagine Dragons", "Believer", hash = sameFile)
        val retagged = key("imagine dragons feat. nobody", "Believer (2017)", 207_000, hash = sameFile)
        assertTrue(tagged.matches(retagged))
    }

    @Test
    fun twoDifferentFilesDoNotMatchOnTagsAlone() {
        val one = key("A", "Track", hash = "11".repeat(32))
        val other = key("A", "Track", hash = "22".repeat(32))
        assertFalse(one.matches(other))
    }

    @Test
    fun withoutAHashTheTagsAndTheLengthDecide() {
        assertTrue(key("Мельница", "Ночная кобыла", 210_000).matches(key("мельница", "ночная кобыла", 210_400)))
        // A second of slack, because one side may be estimating a variable bitrate.
        assertTrue(key("A", "B", 200_000).matches(key("A", "B", 201_000)))
        assertFalse(key("A", "B", 200_000).matches(key("A", "B", 201_001)))
        assertFalse(key("A", "B").matches(key("C", "B")))
    }

    @Test
    fun aTrackThatNamesNothingMatchesNothing() {
        assertFalse(key("", "").matches(key("", "")))
        assertFalse(key("A", "").matches(key("A", "")))
    }

    @Test
    fun theBucketAgreesWhereMatchingDoes() {
        // Anything that matches has to fall in the same bucket, or a lookup would never find it.
        // Including the two cases that nearly caught this out: one side carrying a hash and the
        // other not, and two durations a millisecond apart on either side of a whole second.
        val tagged = key("Мельница", "Ночная кобыла", 210_000, hash = "11".repeat(32))
        val untagged = key("мельница", "ночная кобыла", 210_400)
        assertTrue(tagged.matches(untagged))
        assertEquals(tagged.bucket, untagged.bucket)

        val below = key("A", "B", 210_999)
        val above = key("A", "B", 211_001)
        assertTrue(below.matches(above))
        assertEquals(below.bucket, above.bucket)
    }
}
