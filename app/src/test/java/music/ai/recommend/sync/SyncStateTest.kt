package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncStateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun stateFile() = File(folder.root, "sync/state.json")

    private fun waiting(at: Long, title: String) = SyncState.Waiting(
        deviceId = "22".repeat(16),
        record = PlayRecord(at, 100_000, 200_000, TrackKey("aa".repeat(32), 200_000, "artist", title))
    )

    @Test
    fun aFreshStateIsEmptyAndNotAnError() {
        val state = SyncState.load(stateFile())
        assertEquals(0L, state.sequence)
        assertTrue(state.progress.isEmpty())
        assertTrue(state.unresolved.isEmpty())
        assertTrue(state.aliases.isEmpty())
        assertNull(state.appendWorks)
    }

    @Test
    fun everythingSurvivesBeingSavedAndRead() {
        val file = stateFile()
        val state = SyncState.load(file)
        state.sequence = 42
        state.vectorSequence = 7
        state.vectorTerms = "abc/3/512"
        state.progress["phone/history/2027-01-15.jsonl"] = 7
        state.progress["phone/history/2027-01-14.jsonl"] = 19
        state.rememberUnresolved(listOf(waiting(1_700_000_000_000, "late"), waiting(1_700_000_001_000, "later")))
        state.aliases["cc".repeat(32)] = "99"
        state.appendWorks = false
        assertTrue(state.save())

        val read = SyncState.load(file)
        assertEquals(42L, read.sequence)
        // The two counters are separate streams: one for both would leave gaps in each.
        assertEquals(7L, read.vectorSequence)
        assertEquals("abc/3/512", read.vectorTerms)
        assertEquals(mapOf(
            "phone/history/2027-01-15.jsonl" to 7,
            "phone/history/2027-01-14.jsonl" to 19
        ), read.progress)
        assertEquals(listOf("late", "later"), read.unresolved.map { it.record.track.title })
        assertEquals("22".repeat(16), read.unresolved.first().deviceId)
        assertEquals(mapOf("cc".repeat(32) to "99"), read.aliases)
        assertEquals(false, read.appendWorks)
    }

    @Test
    fun aStateFileFromBeforeAliasesWereTextStillReads() {
        // The aliases were numbers, because a track here is a MediaStore id; they became text so
        // that the desktop, where a track is a path, can keep its own in the same file.
        val file = stateFile()
        file.parentFile!!.mkdirs()
        val hash = "dd".repeat(32)
        file.writeText("{\"v\":1,\"sequence\":3,\"exportedUpTo\":11,\"aliases\":{\"$hash\":99}}")

        val read = SyncState.load(file)
        assertEquals(3L, read.sequence)
        assertEquals(11L, read.exportedUpTo)
        assertEquals(mapOf(hash to "99"), read.aliases)
    }

    @Test
    fun aHalfWrittenStateFileIsTreatedAsNone() {
        // Reading back a sequence that went backwards would collide with batches already written.
        val file = stateFile()
        file.parentFile!!.mkdirs()
        file.writeText("""{"v":1,"sequence":42,"progre""")
        val state = SyncState.load(file)
        assertEquals(0L, state.sequence)
    }

    @Test
    fun rubbishInAFieldCostsThatFieldAndNotTheFile() {
        val file = stateFile()
        file.parentFile!!.mkdirs()
        // A record that names no track cannot be used, and the rest of the state still can.
        file.writeText("""{"sequence":5,"unresolved":[{"at":1,"k":{"d":1},"dev":"x"}]}""")
        val state = SyncState.load(file)
        assertEquals(5L, state.sequence)
        assertTrue(state.unresolved.isEmpty())
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        val file = stateFile()
        SyncState.load(file).save()
        assertEquals(listOf("state.json"), file.parentFile!!.list()!!.sorted())
    }

    @Test
    fun theWaitingListDoesNotGrowWithoutBound() {
        val state = SyncState.load(stateFile())
        // A folder describing a library this device will never have.
        state.rememberUnresolved((1..SyncState.MAX_UNRESOLVED + 500).map { waiting(it.toLong(), "track $it") })
        assertEquals(SyncState.MAX_UNRESOLVED, state.unresolved.size)
        // The oldest went: a listen that has waited longest is the least likely to find its file.
        assertEquals("track 501", state.unresolved.first().record.track.title)
    }

    @Test
    fun changingTransportStartsEveryOutboxAgainButNotTheVectors() {
        val file = stateFile()
        val state = SyncState.load(file)
        state.exportedUpTo = 9_000L
        state.playlistsPublishedUpTo = 42L
        state.playlistsSeeded = true
        state.mixPublished = "2026-11-08#1"
        state.progress["aura/v1/other/history/2026-11-08.jsonl"] = 900
        // The counters that must *not* move: a batch number going backwards would collide with
        // batches already out there, and the vectors are the one thing not worth re-writing.
        state.sequence = 17L
        state.vectorSequence = 5L
        state.transport = "folder"

        state.startOutboxAgain()
        state.transport = "direct"
        assertTrue(state.save())

        val back = SyncState.load(file)
        assertEquals(0L, back.exportedUpTo)
        assertEquals(0L, back.playlistsPublishedUpTo)
        assertEquals(false, back.playlistsSeeded)
        assertEquals(null, back.mixPublished)
        assertEquals(emptyMap<String, Int>(), back.progress)
        assertEquals("direct", back.transport)
        assertEquals(17L, back.sequence)
        assertEquals(5L, back.vectorSequence)
    }

    @Test
    fun nowhereIsNothingRatherThanAnEmptyNameForSomewhere() {
        // How this went wrong: a device whose settings had not loaded yet looked like "network
        // sync off, no folder chosen" and named that `folder:`. Written into the state, it is a
        // destination nothing can match, so the real one looked like a change of mind — progress
        // cleared, outboxes restarted — and with the coordinator cached, a whole run was spent
        // syncing into a folder with no path.
        assertEquals(null, SyncState.destinationOf(direct = false, folder = null))
        assertEquals(null, SyncState.destinationOf(direct = false, folder = ""))
        assertEquals("direct", SyncState.destinationOf(direct = true, folder = null))
        // The socket needs no folder, and having one changes nothing about where it writes.
        assertEquals("direct", SyncState.destinationOf(direct = true, folder = "/home/user/Sync"))
        assertEquals(
            "folder:content://tree/one",
            SyncState.destinationOf(direct = false, folder = "content://tree/one")
        )
        // Two folders are two destinations, which is the whole point of naming them.
        assertTrue(
            SyncState.destinationOf(direct = false, folder = "/a") !=
                SyncState.destinationOf(direct = false, folder = "/b")
        )
    }

    @Test
    fun aDestinationGetsTheVectorsOnceHoweverOftenTheOwnerSwitches() {
        val file = stateFile()
        val state = SyncState.load(file)
        // Each destination needs them the first time and never again, so toggling the switch back
        // and forth does not re-send eleven megabytes on every flick.
        assertTrue(state.fillVectorsFor("direct"))
        assertFalse(state.fillVectorsFor("direct"))
        assertTrue(state.fillVectorsFor("folder:content://tree/one"))
        assertFalse(state.fillVectorsFor("folder:content://tree/one"))
        // A different folder is a different destination: it is empty, and its files share the
        // other one's names.
        assertTrue(state.fillVectorsFor("folder:content://tree/two"))
        assertTrue(state.save())

        val back = SyncState.load(file)
        assertFalse(back.fillVectorsFor("direct"))
        assertFalse(back.fillVectorsFor("folder:content://tree/one"))
        assertFalse(back.fillVectorsFor("folder:content://tree/two"))
        // And a state file written before this existed remembers nothing, so the destination in
        // use is filled once on the next pass.
        assertTrue(back.fillVectorsFor("folder:content://tree/three"))
    }

    @Test
    fun savingTwiceLeavesTheSecondAnswer() {
        val file = stateFile()
        val state = SyncState.load(file)
        state.sequence = 1
        state.save()
        state.sequence = 2
        state.save()
        assertEquals(2L, SyncState.load(file).sequence)
    }
}
