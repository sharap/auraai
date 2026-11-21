package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMatcherTest {

    private fun key(artist: String, title: String, durationMs: Long = 200_000, hash: String? = null) =
        TrackKey(hash, durationMs, TrackKey.normalise(artist), TrackKey.normalise(title))

    private fun matcher(vararg entries: Pair<Long, TrackKey>) =
        TrackMatcher(entries.map { TrackMatcher.Entry(it.first, it.second) })

    private val hashA = "aa".repeat(32)
    private val hashB = "bb".repeat(32)

    @Test
    fun theSameBytesNeedNoWeighing() {
        val matcher = matcher(7L to key("Imagine Dragons", "Believer", 207_000, hashA))
        // The other device has the file under a different name, with the tags stripped.
        assertEquals(7L, matcher.resolve(TrackKey(hashA, 0, "", "")))
    }

    @Test
    fun aHashThatDoesNotMatchDoesNotVetoTheTags() {
        // A flac turned into an mp3 on one device: different bytes, same music.
        val matcher = matcher(7L to key("Мельница", "Ночная кобыла", 210_000, hashA))
        assertEquals(7L, matcher.resolve(key("мельница", "ночная кобыла", 210_400, hashB)))
    }

    @Test
    fun theTagsAndTheLengthDecideWhenThereIsNoHash() {
        val matcher = matcher(7L to key("Нервы", "Самый дорогой человек", 190_000))
        assertEquals(7L, matcher.resolve(key("нервы", "самый дорогой человек", 190_900)))
        assertNull(matcher.resolve(key("нервы", "самый дорогой человек", 191_001)))
        assertNull(matcher.resolve(key("нервы", "другая песня", 190_000)))
    }

    @Test
    fun theVersionsAreToldApartByLength() {
        val matcher = matcher(
            1L to key("Artist", "Track", 200_000),
            2L to key("Artist", "Track", 420_000)
        )
        assertEquals(1L, matcher.resolve(key("artist", "track", 200_300)))
        assertEquals(2L, matcher.resolve(key("artist", "track", 419_800)))
        assertNull(matcher.resolve(key("artist", "track", 300_000)))
    }

    @Test
    fun theClosestLengthWinsAndTiesGoToTheFirstListed() {
        val matcher = matcher(
            1L to key("Artist", "Track", 200_000),
            2L to key("Artist", "Track", 200_400),
            3L to key("Artist", "Track", 200_000)
        )
        assertEquals(2L, matcher.resolve(key("artist", "track", 200_500)))
        // Two at the same distance: the one the library listed first, so a library listed the same
        // way twice resolves the same way twice.
        assertEquals(1L, matcher.resolve(key("artist", "track", 200_000)))
    }

    @Test
    fun aTrackThisLibraryHasNotGotResolvesToNothing() {
        val matcher = matcher(7L to key("Artist", "Track"))
        assertNull(matcher.resolve(key("Somebody", "Else")))
        assertNull(matcher.resolve(TrackKey(hashB, 200_000, "", "")))
    }

    @Test
    fun aKeyThatNamesNothingResolvesToNothing() {
        val matcher = matcher(7L to key("Artist", "Track"))
        assertNull(matcher.resolve(TrackKey(null, 200_000, "", "")))
        assertNull(matcher.resolve(TrackKey(null, 0, "artist", "")))
    }

    @Test
    fun aLocalTrackWithNoTitleIsNotMatchedByTagsButStillByItsHash() {
        val matcher = matcher(7L to TrackKey(hashA, 200_000, "", ""))
        assertEquals(7L, matcher.resolve(TrackKey(hashA, 200_000, "", "")))
        assertNull(matcher.resolve(TrackKey(null, 200_000, "", "")))
    }

    @Test
    fun aHashResolvedByTagsIsRememberedForNextTime() {
        val matcher = matcher(7L to key("Artist", "Track", 200_000, hashA))
        // First time the tags do the work, because the hashes differ.
        assertEquals(7L, matcher.resolve(key("artist", "track", 200_000, hashB)))
        // Now the other device's hash is known, so a record with nothing but that hash lands.
        assertEquals(7L, matcher.resolve(TrackKey(hashB, 0, "", "")))
        assertEquals(mapOf(hashB to 7L), matcher.learnedAliases())
    }

    @Test
    fun aHashCanBePointedAtATrackFromOutside() {
        // What the vectors will do for a file that was both re-encoded and retagged, and what the
        // owner could do by hand.
        val matcher = matcher(7L to key("Artist", "Track"))
        assertNull(matcher.resolve(TrackKey(hashB, 0, "", "")))
        matcher.remember(hashB, 7L)
        assertEquals(7L, matcher.resolve(TrackKey(hashB, 0, "", "")))
    }

    @Test
    fun whatCannotBePlacedIsKeptAndNotDropped() {
        val matcher = matcher(7L to key("Artist", "Here", 200_000))
        val here = incoming(1, key("artist", "here", 200_000))
        val notHere = incoming(2, key("artist", "elsewhere", 200_000))
        val split = matcher.split(listOf(here, notHere))
        assertEquals(listOf(7L), split.resolved.map { it.id })
        assertEquals(listOf(here), split.resolved.map { it.incoming })
        assertEquals(listOf(notHere), split.unresolved)
    }

    @Test
    fun theSameListensResolveOnceTheTrackArrives() {
        val waiting = listOf(incoming(1, key("Artist", "Late", 200_000)))
        assertEquals(1, matcher(7L to key("Artist", "Here")).split(waiting).unresolved.size)
        // The file is copied over and scanned; the matcher is built again from a larger library.
        val afterScan = matcher(7L to key("Artist", "Here"), 8L to key("Artist", "Late", 200_000))
        assertEquals(listOf(8L), afterScan.split(waiting).resolved.map { it.id })
    }

    @Test
    fun aLibraryWithTwoCopiesOfATrackPicksOneOfThemAndKeepsPicking() {
        // Duplicate files are ordinary. Either answer is right; giving the same one twice is not
        // optional, or the same listen would land on a different track on every pass.
        val matcher = matcher(
            1L to key("Artist", "Track", 200_000, hashA),
            2L to key("Artist", "Track", 200_000, hashB)
        )
        val resolved = matcher.resolve(key("artist", "track", 200_000))
        assertEquals(1L, resolved)
        assertEquals(resolved, matcher.resolve(key("artist", "track", 200_000)))
    }

    @Test
    fun aPathIsAsGoodAnIdentifierAsANumber() {
        // The desktop names tracks by path, so the matcher must not care what an id is.
        val matcher = TrackMatcher(listOf(
            TrackMatcher.Entry("/music/a.mp3", key("Artist", "Track", 200_000, hashA))
        ))
        assertEquals("/music/a.mp3", matcher.resolve(TrackKey(hashA, 0, "", "")))
    }

    private fun incoming(at: Long, key: TrackKey) = HistoryMerge.Incoming(
        deviceId = "22".repeat(16),
        sequence = 1,
        record = PlayRecord(at, 100_000, key.durationMs, key)
    )
}
