package music.ai.recommend.sync

import org.junit.Assert.assertEquals
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
}
