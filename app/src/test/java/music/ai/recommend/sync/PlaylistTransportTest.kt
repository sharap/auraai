package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbox and the reader for playlist changes, over a folder that is a map.
 *
 * The walking is shared with history and vectors and tested with them; what is particular here is
 * that no day is ever too old to open. The log *is* the playlists, so a change from two years ago
 * is still load-bearing and a device meeting the folder for the first time has to read all of it.
 */
class PlaylistTransportTest {

    private val phone = "aa".repeat(16)
    private val desktop = "bb".repeat(16)

    /** 2026-11-05T12:00:00Z and a year earlier. */
    private val now = 1_793_880_000_000L
    private val longAgo = now - 400L * 24 * 60 * 60 * 1000

    private class Folder : SyncFolder {
        val contents = LinkedHashMap<String, MutableList<String>>()

        override fun append(relativePath: String, lines: List<String>): Boolean {
            contents.getOrPut("$OWNER/$relativePath") { ArrayList() }.addAll(lines)
            return true
        }

        fun put(deviceId: String, path: String, lines: List<String>) {
            contents.getOrPut("$deviceId/$path") { ArrayList() }.addAll(lines)
        }

        override fun devices(): List<String> = contents.keys.map { it.substringBefore('/') }.distinct()

        override fun files(deviceId: String, inside: String): List<String> = contents.keys
            .filter { it.startsWith("$deviceId/$inside/") }
            .map { it.substringAfter('/') }

        override fun lines(deviceId: String, relativePath: String): List<String>? =
            contents["$deviceId/$relativePath"]

        companion object {
            const val OWNER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
    }

    private fun op(kind: PlaylistOp.Kind, seq: Long, device: String = phone, playlist: String? = null, title: String? = null) =
        PlaylistOp(
            kind, seq, device, now, playlist,
            title?.let { TrackKey(null, 200_000, "мельница", it) }
        )

    private fun pass(folder: Folder, progress: MutableMap<String, Int>, reader: String = desktop) =
        PlaylistReader(reader, folder, progress).read().also { progress.putAll(it.advanced) }

    @Test
    fun aChangeCrossesTheFolderAndComesBackTheSame() {
        val folder = Folder()
        val writer = PlaylistWriter(Folder.OWNER, folder)
        val added = op(PlaylistOp.Kind.ADD, 1, device = Folder.OWNER, playlist = "вечер", title = "один")
        val hearted = op(PlaylistOp.Kind.ADD, 2, device = Folder.OWNER, title = "два")
        writer.offer(added)
        writer.offer(hearted)
        assertEquals(2, writer.flush(now, force = true))
        assertEquals(
            listOf("${Folder.OWNER}/playlists/2026-11-05.jsonl"),
            folder.contents.keys.toList()
        )

        val progress = HashMap<String, Int>()
        val outcome = pass(folder, progress)
        assertEquals(listOf(added, hearted), outcome.ops)
        assertFalse(outcome.more)

        // Read again and nothing comes twice.
        assertEquals(0, pass(folder, progress).ops.size)
        assertEquals(1, pass(folder, progress).filesSkipped)
    }

    @Test
    fun aChangeFromTwoYearsAgoIsStillRead() {
        // The difference from history: there the store keeps a year and an older file holds
        // nothing it wants, so it is skipped by its name alone. Here the log is the playlists, and
        // the change that created one is as load-bearing as the one made this morning.
        val folder = Folder()
        folder.put(phone, PlaylistWriter.dayFile(longAgo), listOf(
            SyncBatch(phone, 1, SyncBatch.SEALING_NONE, listOf(
                op(PlaylistOp.Kind.CREATE, 1, playlist = "старый").toJson()
            )).encode()
        ))
        folder.put(phone, PlaylistWriter.dayFile(now), listOf(
            SyncBatch(phone, 2, SyncBatch.SEALING_NONE, listOf(
                op(PlaylistOp.Kind.ADD, 2, playlist = "старый", title = "один").toJson()
            )).encode()
        ))

        val outcome = pass(folder, HashMap())
        assertEquals(2, outcome.ops.size)
        assertEquals(0, outcome.filesSkipped)
        assertEquals(
            mapOf("старый" to listOf(TrackKey(null, 200_000, "мельница", "один"))),
            PlaylistReplay.replay(outcome.ops).playlists
        )
    }

    @Test
    fun aDeviceDoesNotReadItsOwnChangesBack() {
        val folder = Folder()
        val writer = PlaylistWriter(Folder.OWNER, folder)
        writer.offer(op(PlaylistOp.Kind.ADD, 1, device = Folder.OWNER, title = "своё"))
        writer.flush(now, force = true)

        val outcome = pass(folder, HashMap(), reader = Folder.OWNER)
        assertEquals(0, outcome.ops.size)
        assertEquals(0, outcome.filesRead)
    }

    @Test
    fun aChangeOfAKindThisVersionDoesNotKnowIsCountedAndSkipped() {
        // A newer version's operation. Dropping it is a decision taken in PlaylistOp; what matters
        // here is that it does not take the rest of the batch with it.
        val folder = Folder()
        val known = op(PlaylistOp.Kind.ADD, 1, playlist = "p", title = "один").toJson()
        val future = com.google.gson.JsonParser
            .parseString("""{"k":"reorder","s":2,"d":"$phone","at":$now,"p":"p"}""").asJsonObject
        folder.put(phone, PlaylistWriter.dayFile(now), listOf(
            SyncBatch(phone, 1, SyncBatch.SEALING_NONE, listOf(known, future)).encode()
        ))

        val outcome = pass(folder, HashMap())
        assertEquals(1, outcome.ops.size)
        assertEquals(1, outcome.linesUnreadable)
        assertTrue(outcome.ops.single().kind == PlaylistOp.Kind.ADD)
    }

    @Test
    fun theOutboxKeepsWhatTheFolderWouldNotTake() {
        val refusing = object : SyncSink {
            var refuse = true
            override fun append(relativePath: String, lines: List<String>) = !refuse
        }
        val writer = PlaylistWriter(phone, refusing)
        writer.offer(op(PlaylistOp.Kind.ADD, 1, title = "один"))
        assertEquals(0, writer.flush(now, force = true))
        assertEquals(1, writer.pending)
        // The batch number is spent only once the write has landed, so a reader never sees a gap
        // where there was only an unreachable folder.
        assertEquals(0L, writer.sequence)

        refusing.refuse = false
        assertEquals(1, writer.flush(now, force = true))
        assertEquals(1L, writer.sequence)
    }

    @Test
    fun threeKindsOfFileShareOneFolderAndOneProgressMap() {
        // One device directory now holds history, vectors and playlist changes, read by three
        // readers off one map. The keys carry the file's own directory, so none can mark another's
        // file as read.
        val folder = Folder()
        PlaylistWriter(phone, folder).let { it.offer(op(PlaylistOp.Kind.ADD, 1, title = "один")); it.flush(now, force = true) }
        folder.put(phone, HistoryWriter.dayFile(now), listOf(
            SyncBatch(phone, 1, SyncBatch.SEALING_NONE, listOf(
                PlayRecord(now, 180_000, 200_000, TrackKey(null, 200_000, "мельница", "один")).toJson()
            )).encode()
        ))
        folder.put(phone, VectorWriter.dayFile(now), listOf(
            SyncBatch(phone, 1, SyncBatch.SEALING_NONE, listOf(
                VectorRecord(
                    TrackKey(null, 200_000, "мельница", "один"), 7_000L,
                    music.ai.recommend.ai.Mp3Excerpt.DECODER, "m", 3, FloatArray(512) { 0.1f }
                ).toJson()
            )).encode()
        ))

        val progress = HashMap<String, Int>()
        assertEquals(1, PlaylistReader(desktop, folder, progress).read().also { progress.putAll(it.advanced) }.ops.size)
        assertEquals(1, VectorReader(desktop, folder, progress).read().also { progress.putAll(it.advanced) }.records.size)
        assertEquals(
            1,
            HistoryReader(desktop, folder, progress).read(emptySet(), now, 30)
                .also { progress.putAll(it.advanced) }.merge.accepted.size
        )
        assertEquals(
            setOf(
                "${Folder.OWNER}/playlists/2026-11-05.jsonl",
                "$phone/history/2026-11-05.jsonl",
                "$phone/vectors/2026-11-05.jsonl"
            ),
            progress.keys
        )
    }
}
