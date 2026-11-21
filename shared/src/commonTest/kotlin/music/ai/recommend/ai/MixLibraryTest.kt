package music.ai.recommend.ai

import music.ai.recommend.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library both the daily mix and the wave are built from.
 *
 * Written when this moved into the shared module, because until then each port assembled it in its
 * own way and one of those ways was wrong in two places: the desktop grouped by the artist tag
 * alone, where the phone also reads an act off the file name, and it built the duplicate key from
 * the raw title, which on an "Artist - Song.mp3" file is a different string from the tagged one.
 */
class MixLibraryTest {

    private fun song(
        id: Long,
        title: String,
        artist: String = "",
        folder: String = "music",
        path: String = "/m/$title.mp3"
    ) = Song(
        id = id, title = title, artist = artist, album = "", albumId = 0L, duration = 180_000L,
        uri = path, path = path, folderName = folder
    )

    @Test
    fun aTrackWithNoVectorIsLeftOut() {
        val songs = listOf(song(1, "One"), song(2, "Two"))
        val library = mixLibrary(songs, { it.id }, mapOf(1L to floatArrayOf(1f, 0f)), emptyMap())
        assertEquals(listOf(1L), library.map { it.track })
    }

    /**
     * A zero vector is a port's note that the file could not be decoded, and it is stored so the
     * scan does not retry it. Treated as a direction it would sit the same distance from
     * everything, so the callers filter it out before this point; what this pins is that a missing
     * vector is never invented here.
     */
    @Test
    fun noTrackIsGivenAVectorItDoesNotHave() {
        val library = mixLibrary(listOf(song(1, "One")), { it.id }, emptyMap(), emptyMap())
        assertTrue(library.isEmpty())
    }

    @Test
    fun oneIdentityAppearsOnce() {
        val twice = listOf(song(1, "One"), song(1, "One again"))
        val library = mixLibrary(twice, { it.id }, mapOf(1L to floatArrayOf(1f)), emptyMap())
        assertEquals(1, library.size)
    }

    @Test
    fun theLastTimeATrackWasHeardComesAlong() {
        val library = mixLibrary(
            listOf(song(1, "One")), { it.id }, mapOf(1L to floatArrayOf(1f)), mapOf(1L to 42L)
        )
        assertEquals(42L, library.single().lastPlayedAt)
    }

    /** The act, tagged or read off the file name — whichever the file happens to carry. */
    @Test
    fun theGroupIsTheActHoweverTheFileNamesIt()  {
        val vectors = mapOf(1L to floatArrayOf(1f), 2L to floatArrayOf(1f))
        val library = mixLibrary(
            listOf(song(1, "Song", artist = "The Act"), song(2, "The Act - Song")),
            { it.id }, vectors, emptyMap()
        )
        assertEquals(listOf("the act", "the act"), library.map { it.group })
    }

    /**
     * The whole point of the shared formula: one recording, filed twice — once with tags, once as
     * "Artist - Song.mp3" — is one duplicate key. The desktop's own formula gave these two
     * different keys, so both could land in the same daily playlist.
     */
    @Test
    fun oneRecordingFiledTwiceIsOneDuplicateKey() {
        val vectors = mapOf(1L to floatArrayOf(1f), 2L to floatArrayOf(1f))
        val library = mixLibrary(
            listOf(
                song(1, "Song", artist = "The Act", path = "/m/tagged.mp3"),
                song(2, "The Act - Song", path = "/m/untagged.mp3")
            ),
            { it.id }, vectors, emptyMap(), duplicates = true
        )
        assertEquals(1, library.map { it.duplicateKey }.distinct().size)
        assertEquals("the act|song", library.first().duplicateKey)
    }

    /** The wave asks for no keys: it offers one track at a time and a skip is a cheap answer. */
    @Test
    fun withoutDuplicatesTheKeyIsEmpty() {
        val library = mixLibrary(
            listOf(song(1, "Song", artist = "The Act")), { it.id }, mapOf(1L to floatArrayOf(1f)), emptyMap()
        )
        assertEquals("", library.single().duplicateKey)
    }

    /** A path identifies a track on the desktop, and nothing here cares which it is. */
    @Test
    fun aPathWorksAsAnIdentityJustAsWell() {
        val songs = listOf(song(1, "One", path = "/m/one.mp3"))
        val library = mixLibrary(songs, { it.path }, mapOf("/m/one.mp3" to floatArrayOf(1f)), emptyMap())
        assertEquals("/m/one.mp3", library.single().track)
    }
}
