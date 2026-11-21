package music.ai.recommend.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartAlbumNamingTest {

    /**
     * Label 0 sits close to everything (the "this is music" direction), so on raw similarity it
     * wins for every album. Discounted by the library baseline, each album gets its own label.
     */
    @Test
    fun labelsAreScoredRelativeToTheLibrary() {
        val common = floatArrayOf(1f, 0f, 0f)
        val a = floatArrayOf(0.8f, 0.6f, 0f)
        val b = floatArrayOf(0.8f, 0f, 0.6f)
        val library = floatArrayOf(0.95f, 0.22f, 0.22f)
        val labels = listOf(common, floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))

        val chosen = SmartAlbumNaming.bestLabels(listOf(a, b), library, labels)

        assertArrayEquals(intArrayOf(1, 2), chosen)
    }

    @Test
    fun repeatedTitlesAreToldApartByArtistThenNumber() {
        assertEquals(
            listOf("Транс", "Поп", "Транс · Armin van Buuren", "Транс 2", "Транс 3"),
            SmartAlbumNaming.uniqueTitles(
                listOf("Транс", "Поп", "Транс", "Транс", "Транс"),
                listOf("Armin van Buuren", null, "Armin van Buuren", "Armin van Buuren", null)
            )
        )
    }

    @Test
    fun dominantArtistNeedsHalfTheAlbum() {
        val album = listOf("Armik" to "Rain", "Armik" to "Flamenco", "<unknown>" to "Track 1", "Other" to "x")
        assertEquals("Armik", SmartAlbumNaming.dominantArtist(album))
        assertNull(SmartAlbumNaming.dominantArtist(album + ("Third" to "y")))
    }

    @Test
    fun artistFallsBackToTheTitle() {
        assertEquals("Queen", SmartAlbumNaming.artistOf("Queen", "Bohemian Rhapsody"))
        assertEquals("Валерий Меладзе", SmartAlbumNaming.artistOf("<unknown>", "Валерий Меладзе - Небеса"))
        assertEquals("Armik", SmartAlbumNaming.artistOf("", "03. Armik - Rain"))
        assertNull(SmartAlbumNaming.artistOf("<unknown>", "Track 07"))
    }

    @Test
    fun albumIdIgnoresOrder() {
        assertEquals(
            SmartAlbumNaming.albumId(listOf(3L, 1L, 2L)),
            SmartAlbumNaming.albumId(listOf(1L, 2L, 3L))
        )
    }

    /** A path identifies a track on the desktop, and the id does not care which it is. */
    @Test
    fun anAlbumOfPathsHasAnIdToo() {
        assertEquals(
            SmartAlbumNaming.albumId(listOf("/m/b.mp3", "/m/a.mp3")),
            SmartAlbumNaming.albumId(listOf("/m/a.mp3", "/m/b.mp3"))
        )
    }

    /**
     * Each port reads tags with its own library, and each used to reject only the spelling it saw:
     * MediaStore writes "<unknown>", jaudiotagger leaves "Unknown Artist" or "Unknown". An album of
     * untagged tracks was subtitled "Unknown Artist" on the desktop before the two were merged.
     */
    @Test
    fun everySpellingOfAnUnknownArtistIsNoArtist() {
        assertNull(SmartAlbumNaming.artistOf("<unknown>", "Track 07"))
        assertNull(SmartAlbumNaming.artistOf("Unknown Artist", "Track 07"))
        assertNull(SmartAlbumNaming.artistOf("unknown", "Track 07"))
    }

    @Test
    fun signatureChangesWithTheTracksAndTheModel() {
        val base = SmartAlbumNaming.signature(listOf(1L, 2L, 3L), "FULL")
        assertEquals(base, SmartAlbumNaming.signature(listOf(3L, 2L, 1L), "FULL"))
        assert(base != SmartAlbumNaming.signature(listOf(1L, 2L, 4L), "FULL"))
        assert(base != SmartAlbumNaming.signature(listOf(1L, 2L, 3L), "QUANTIZED"))
    }
}
