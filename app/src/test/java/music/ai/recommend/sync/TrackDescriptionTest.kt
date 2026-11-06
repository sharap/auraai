package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a local track comes to be described to another device.
 *
 * Most of this collection carries no artist tag and is named `Artist - Title (version)`, so the
 * file's name is not a fallback here but the main road. Getting it wrong costs matches, and an
 * unmatched listen is history that silently never arrives.
 */
class TrackDescriptionTest {

    private fun describe(artist: String, title: String, fileName: String, durationMs: Long = 200_000) =
        TrackKey.describe(artist, title, fileName, durationMs)

    @Test
    fun tagsAreUsedWhenThereAreTags() {
        val key = describe("Imagine Dragons", "Believer", "01 - track.mp3")
        assertEquals("imagine dragons", key.artist)
        assertEquals("believer", key.title)
    }

    @Test
    fun theFileNameIsReadWhenTheArtistTagIsMissing() {
        val key = describe("", "", "Нервы - Самый дорогой человек.mp3")
        assertEquals("нервы", key.artist)
        assertEquals("самый дорогой человек", key.title)
    }

    @Test
    fun androidsWordForNoTagsIsNotAnArtist() {
        val key = describe("<unknown>", "", "Мельница - Ночная кобыла.mp3")
        assertEquals("мельница", key.artist)
        assertEquals("ночная кобыла", key.title)
    }

    @Test
    fun everyWordForNoTagsIsNotAnArtist() {
        // Each of these is what some tag reader puts there when the file says nothing, and they
        // have to be recognised on both sides or the same track gets two descriptions: the artist
        // read out of the file's name on the device that knew the word, and the word itself on the
        // one that did not. Found in a real exchange — the desktop had written "unknown artist"
        // into a day's file for most of a collection, where the phone would have written the name.
        for (said in listOf("<unknown>", "Unknown Artist", "unknown artist", "Unknown", "UNKNOWN")) {
            val key = describe(said, "", "Мельница - Ночная кобыла.mp3")
            assertEquals(said, "мельница", key.artist)
            assertEquals(said, "ночная кобыла", key.title)
        }
    }

    @Test
    fun theTwoPlatformsDescribeTheSameFileTheSameWay() {
        // The property that actually matters, stated as a test rather than as a hope. Each row is
        // one file as the two scanners read it: MediaStore on the phone, jaudiotagger on the
        // desktop, which disagree about what to put where a tag says nothing. The keys must come
        // out equal, because the whole of identity-by-tags rests on it.
        val files = listOf(
            // no tags at all: both fall back to the file's name for the title
            Triple("Мельница - Ночная кобыла.mp3", "Мельница - Ночная кобыла", 210_000L),
            Triple("Norma Tale - Кисточка.mp3", "Norma Tale - Кисточка", 190_000L),
            Triple("Epic Score - Liberators.mp3", "Epic Score - Liberators", 157_000L)
        )
        for ((fileName, titleFallback, duration) in files) {
            val onThePhone = TrackKey.describe("<unknown>", titleFallback, fileName, duration)
            val onTheDesktop = TrackKey.describe("Unknown Artist", titleFallback, fileName, duration)
            assertEquals(fileName, onThePhone, onTheDesktop)
            assertTrue(fileName, onThePhone.matches(onTheDesktop))
        }

        // And a file that was read on one side and not on the other: the desktop says "Unknown"
        // for a file it could not open, where it would otherwise have had the tags.
        val unreadable = TrackKey.describe("Unknown", "Мельница - Ночная кобыла", "Мельница - Ночная кобыла.mp3", 210_000)
        val read = TrackKey.describe("<unknown>", "Мельница - Ночная кобыла", "Мельница - Ночная кобыла.mp3", 210_000)
        assertEquals(read, unreadable)
    }

    @Test
    fun aBandActuallyCalledSomethingUnknownIsStillAnArtist() {
        // The words are refused whole, not as a substring: these are real names.
        assertEquals("unknown mortal orchestra", describe("Unknown Mortal Orchestra", "Swim and Sleep", "x.mp3").artist)
        assertEquals("the unknown", describe("The Unknown", "Nothing", "x.mp3").artist)
    }

    @Test
    fun aTitleTagStandsEvenWhenTheNameIsParsed() {
        // The tag is the better source for the title; the name only had to give up the artist.
        val key = describe("", "Ночная кобыла", "Мельница - 02 Ночная Кобыла.mp3")
        assertEquals("мельница", key.artist)
        assertEquals("ночная кобыла", key.title)
    }

    @Test
    fun theVersionInBracketsStaysPartOfTheTitle() {
        val key = describe("", "", "Нервы - Слайдер (Remix).mp3")
        assertEquals("слайдер (remix)", key.title)
    }

    @Test
    fun aDashInsideANameIsNotASeparator() {
        assertEquals("", describe("", "", "Nu-Virgos.mp3").artist)
        assertEquals("nu-virgos", describe("", "", "Nu-Virgos.mp3").title)
        val jarre = describe("", "", "Jean-Michel Jarre - Oxygene.mp3")
        assertEquals("jean-michel jarre", jarre.artist)
        assertEquals("oxygene", jarre.title)
    }

    @Test
    fun theThreeDashesAllSeparate() {
        for (dash in listOf("-", "–", "—")) {
            val key = describe("", "", "Artist $dash Title.mp3")
            assertEquals("artist", key.artist)
            assertEquals("title", key.title)
        }
    }

    @Test
    fun aTrackNumberIsNotAnArtist() {
        val key = describe("", "", "01 - Atlantia.mp3")
        assertEquals("", key.artist)
        assertEquals("01 - atlantia", key.title)
    }

    @Test
    fun onlyTheFirstDashSeparates() {
        val key = describe("", "", "Artist - Title - With A Dash.mp3")
        assertEquals("artist", key.artist)
        assertEquals("title - with a dash", key.title)
    }

    @Test
    fun aNameWithNothingToSplitBecomesTheTitle() {
        val key = describe("", "", "Track 1.wav")
        assertEquals("", key.artist)
        assertEquals("track 1", key.title)
    }

    @Test
    fun theExtensionIsNotPartOfTheName() {
        assertEquals("believer", describe("", "", "Believer.mp3").title)
        assertEquals("believer", describe("", "", "Believer.flac").title)
        assertEquals("believer.live", describe("", "", "Believer.live.mp3").title)
    }

    @Test
    fun twoDevicesDescribingTheSameFileAgree() {
        // The phone has tags from MediaStore; the desktop read none and has only the name.
        val phone = TrackKey.describe("Мельница", "Ночная кобыла", "Мельница - Ночная кобыла.mp3", 210_000)
        val desktop = TrackKey.describe("", "", "Мельница - Ночная кобыла.mp3", 210_400)
        assertEquals(phone.bucket, desktop.bucket)
        org.junit.Assert.assertTrue(phone.matches(desktop))
    }

    @Test
    fun aCharacterNobodyCanSeeDoesNotMakeItAnotherTrack() {
        // Found in the folder, not imagined. The desktop's tag reader hands back the byte order
        // mark at the front of a tag; MediaStore has already swallowed it. So one device wrote
        // "\uFEFFмементо мори" and the other "мементо мори" for the same file, and the two could
        // never be the same track: no listen of it travelled, no vector of it was accepted, and
        // in a playlist it would have been two entries.
        val desktop = TrackKey.describe("\uFEFFксюшенька.", "\uFEFFмементо мори", "01.mp3", 153_000)
        val phone = TrackKey.describe("ксюшенька.", "мементо мори", "01.mp3", 153_260)
        assertEquals(phone.artist, desktop.artist)
        assertEquals(phone.title, desktop.title)
        assertEquals(phone.bucket, desktop.bucket)
        org.junit.Assert.assertTrue(phone.matches(desktop))

        // The others that take up no space and say nothing, including inside a name.
        assertEquals("abc", TrackKey.normalise("a\u200Bb\u200Dc"))
        assertEquals("abc", TrackKey.normalise("ab\u00ADc"))
        assertEquals("a b", TrackKey.normalise("a \u2060 b"))
        // And a name that is nothing but invisible characters is nothing at all, rather than a
        // name made of them that only one device would ever write.
        assertEquals("", TrackKey.normalise("\uFEFF\u200B"))
    }
}
