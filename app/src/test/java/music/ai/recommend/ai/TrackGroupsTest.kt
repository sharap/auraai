package music.ai.recommend.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cases that come from the library this was written for, where five tracks in six carry no
 * artist tag and the name is all there is to go on.
 */
class TrackGroupsTest {

    @Test
    fun theTagWinsWhenItIsThere() {
        assertEquals("imagine dragons", TrackGroups.of("Imagine Dragons", "Believer", "like"))
        // Even when the name would parse differently: the tag was set by someone, the name by a
        // download.
        assertEquals("kygo, imagine dragons",
            TrackGroups.of("Kygo, Imagine Dragons", "Born To Be Yours", "like"))
    }

    @Test
    fun theArtistIsReadOffTheFileName() {
        assertEquals("imagine dragons",
            TrackGroups.of("<unknown>", "Imagine Dragons - Believer (Remix)", "like"))
        assertEquals("мельница", TrackGroups.of("", "Мельница — Дорога сна", "folder"))
        // Spacing on one side only, which downloads produce often enough.
        assertEquals("flёur", TrackGroups.of("<unknown>", "Flёur -Шелкопряд", "like"))
        assertEquals("thefatrat", TrackGroups.of("<unknown>", "TheFatRat- Unity", "like"))
    }

    /** Hyphenated names are common, and splitting on the first hyphen would file them wrong. */
    @Test
    fun aHyphenInsideANameIsNotASeparator() {
        assertEquals("nu-virgos", TrackGroups.of("<unknown>", "Nu-Virgos - Stop", "like"))
        assertEquals("jean-michel jarre",
            TrackGroups.of("<unknown>", "Jean-Michel Jarre - Oxygene IV", "like"))
        assertNull(TrackGroups.artistInName("Nu-Virgos"))
    }

    /** A number in front is the track's place on a disc, not an act. */
    @Test
    fun aTrackNumberIsNotAnArtist() {
        assertEquals("одинокий пастух", TrackGroups.of("<unknown>", "01 - Intro", "Одинокий пастух"))
        assertEquals("литургия праздник", TrackGroups.of("<unknown>", "12. - Хвалите", "Литургия праздник"))
    }

    /** A guest is not another act: both belong with whoever is first. */
    @Test
    fun aGuestBelongsWithTheAct() {
        assertEquals("thepianoguys",
            TrackGroups.of("<unknown>", "ThePianoGuys feat. Lindsey Stirling - Mission", "like"))
        assertEquals("armin van buuren",
            TrackGroups.of("<unknown>", "Armin van Buuren ft. Sharon den Adel - In And Out Of Love", "like"))
    }

    @Test
    fun withoutADashTheFolderIsUsed() {
        assertEquals("exm", TrackGroups.of("<unknown>", "driving1", "exm"))
        // One letter in front of a dash is a word, not a name.
        assertEquals("folder", TrackGroups.of("<unknown>", "Я - не твой", "folder"))
        // Nothing after the dash either.
        assertEquals("folder", TrackGroups.of("<unknown>", "Instrumental -", "folder"))
    }

    @Test
    fun theSongLosesTheArtistPrefix() {
        assertEquals("Believer (Remix)", TrackGroups.songInName("Imagine Dragons - Believer (Remix)"))
        assertEquals("Дорога сна", TrackGroups.songInName("Мельница — Дорога сна"))
        // Left alone when there is no artist in front of it.
        assertEquals("driving1", TrackGroups.songInName("driving1"))
        assertEquals("01 - Intro", TrackGroups.songInName("01 - Intro"))
    }
}
