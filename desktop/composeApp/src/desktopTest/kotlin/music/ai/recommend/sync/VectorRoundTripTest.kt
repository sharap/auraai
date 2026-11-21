package music.ai.recommend.sync

import kotlinx.coroutines.runBlocking
import music.ai.recommend.ai.Excerpt
import music.ai.recommend.db.ANALYSIS_VERSION
import music.ai.recommend.db.DesktopMusicDao
import music.ai.recommend.db.EMBEDDING_DIMENSIONS
import music.ai.recommend.db.EmbeddingEntity
import music.ai.recommend.model.Song
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Carrying embeddings, end to end, over this port's own store and folder.
 *
 * The format, the terms, the outbox and the reader are shared with the phone and tested there. What
 * this says is that the joins hold here: a track is a path, a vector is a row in a packed file, and
 * the excerpt is named from the duration the library scanner read — which is the same one the
 * analysis uses, and the reason this side needs no file open to answer.
 */
class VectorRoundTripTest {

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)
    private val model = "3ecc72d27740e2a09ced20cf22fd6244122e5e506008763a0f368b3b4ff6eac8"
    private val now = 1_793_880_000_000L

    private lateinit var baseDir: File
    private lateinit var folder: File

    /**
     * Two real mp3s and one name.
     *
     * Real files because the excerpt is named by walking the file's frames, which is the whole
     * point of this feature working at all — and because the durations below are deliberately not
     * the files' own: the fixture is 25 seconds, and the test insists the window comes from the
     * frames and not from what the tag reader claims.
     */
    private lateinit var tracks: List<Song>

    private val fixture = File("src/desktopTest/resources/mp3_excerpt_fixture.mp3")

    private fun song(path: String, artist: String, title: String, duration: Long) = Song(
        id = 0,
        title = title,
        artist = artist,
        album = "",
        albumId = 0,
        duration = duration,
        uri = path,
        path = path,
        folderName = "music"
    )

    @BeforeTest
    fun emptyEverything() {
        baseDir = File(System.getProperty("java.io.tmpdir"), "aimusic-vectors-${System.nanoTime()}")
        folder = File(baseDir, "shared").apply { mkdirs() }
        val music = File(baseDir, "music").apply { mkdirs() }
        val believer = File(music, "Imagine Dragons - Believer.mp3")
        val mare = File(music, "Мельница - Ночная кобыла.mp3")
        fixture.copyTo(believer)
        fixture.copyTo(mare)
        tracks = listOf(
            // The durations are the tag reader's story and the files are 25 s of frames; the gap
            // is on purpose.
            song(believer.path, "Imagine Dragons", "Believer", 207_000),
            song(mare.path, "", "", 210_000),
            // An ogg: one of the formats still left to the platform, so its vectors stay at
            // home. It was an m4a until m4a gained a shared decoder.
            song(File(music, "01 - Atlantia.ogg").path, "Secret Garden", "Atlantia", 179_000)
        )
    }

    @AfterTest
    fun tidyUp() {
        baseDir.deleteRecursively()
    }

    private val dao by lazy { DesktopMusicDao(File(baseDir, "data")) }

    private fun vector(seed: Int) = FloatArray(EMBEDDING_DIMENSIONS) { ((it + seed) % 17) * 0.05f + 0.013f }

    private fun coordinator(
        deviceId: String,
        library: List<Song> = tracks,
        analysingWith: String = model
    ): SyncCoordinator<String> =
        SyncCoordinator(
            deviceId = deviceId,
            // The same file for every coordinator of a device, as a restart would find it: a test
            // about what a device remembers is meaningless with a fresh state each time.
            state = SyncState.load(File(baseDir, "state-$deviceId.json")),
            store = PlayLogStore(dao, { library }),
            folder = { FileSyncFolder(folder, deviceId) },
            vectors = DaoVectorStore(dao, { library }, analysingWith)
        )

    private suspend fun analysed(vararg indices: Int) {
        for (index in indices) {
            dao.insertEmbedding(EmbeddingEntity(tracks[index].path, vector(index).toList()))
        }
    }

    @Test
    fun anMp3VectorCrossesTheFolderAndComesBackTheSame() = runBlocking {
        analysed(0, 1)

        val pushed = coordinator(first).pushVectors(now)
        assertEquals(2, pushed.written, "отправлено: $pushed")
        assertEquals(0, pushed.couldNotTravel)

        // As the other machine: the same library, none of it analysed.
        dao.clearAllEmbeddings()
        val pulled = coordinator(second).pullVectors(now)
        assertEquals(2, pulled.takenIn, "принято: $pulled")
        assertTrue(pulled.refused.isEmpty(), "отказы: ${pulled.refused}")

        val held = dao.getAllEmbeddings().associateBy { it.path }
        assertEquals(setOf(tracks[0].path, tracks[1].path), held.keys)
        for (index in 0..1) {
            assertEquals(
                vector(index).toList(),
                held.getValue(tracks[index].path).vector,
                "вектор ${tracks[index].path}"
            )
        }
        // And what came in goes out again from here, which is what makes the folder restorable;
        // `aVectorTakenInIsOfferedOnwards` is about that on its own.
        assertEquals(2, coordinator(second).pushVectors(now).written)
    }

    @Test
    fun aVectorAskedForByNameArrivesAndTheFolderStaysEmpty() = runBlocking {
        // What the asking machine is missing, said before anything here is analysed: all of it.
        val wanted = coordinator(second).vectorsWanted(50)
        assertEquals(3, wanted.size, "просит: $wanted")

        analysed(0, 1)
        val giver = coordinator(first)
        assertEquals(2, giver.vectorsHeld())
        // The ogg: in the library, no vector, and no shared decoder either — still a gap.
        assertEquals(1, giver.vectorsLacking())

        val served = giver.serveVectors(wanted)
        // Two of the three asked for. The third is held by nobody, and a key that names nothing
        // here is left out rather than answered with an excuse.
        assertEquals(2, served.size, "подано: $served")

        // As the other machine: the same library, none of it analysed.
        dao.clearAllEmbeddings()
        assertEquals(2, coordinator(second).takeInVectors(served), "принято")

        val held = dao.getAllEmbeddings().associateBy { it.path }
        assertEquals(setOf(tracks[0].path, tracks[1].path), held.keys)
        for (index in 0..1) {
            assertEquals(
                vector(index).toList(),
                held.getValue(tracks[index].path).vector,
                "вектор ${tracks[index].path}"
            )
        }

        // Nothing was published to do it, which is the whole point: a socket has somebody to ask.
        assertTrue(
            FileSyncFolder(folder, first).files(first, VectorWriter.DIRECTORY).isEmpty(),
            "по сети по запросу — в папку писать нечего"
        )
        // And what arrived this way is still unoffered, so an owner who switches to a folder
        // tomorrow hands it on exactly as if it had been analysed here.
        assertEquals(2, coordinator(second).pushVectors(now).written)
    }

    @Test
    fun askingForNothingIsAnsweredWithNothing() = runBlocking {
        analysed(0, 1)
        val giver = coordinator(first)
        assertTrue(giver.serveVectors(emptyList()).isEmpty())
        assertEquals(0, giver.takeInVectors(emptyList()))
        // One gap left here — the ogg — and it is named rather than counted, because the name is
        // what the other machine has to look it up by.
        val wanted = giver.vectorsWanted(50)
        assertEquals(1, wanted.size, "просит: $wanted")
        // Lower-cased, as every key is: the one spelling both devices can agree on.
        assertTrue(wanted.single().contains("atlantia"), "ключ: ${wanted.single()}")
    }

    @Test
    fun aVectorFromAFormatWithoutASharedDecoderNeverLeaves() = runBlocking {
        analysed(2)                            // the ogg
        val pushed = coordinator(first).pushVectors(now)
        assertEquals(0, pushed.written)
        assertEquals(1, pushed.couldNotTravel)
        assertTrue(FileSyncFolder(folder, first).files(first, VectorWriter.DIRECTORY).isEmpty())

        // And it is not reconsidered on the next pass: the answer will not change while the
        // analysis version stays the same, and a library of four thousand would be walked again.
        assertEquals(0, coordinator(first).pushVectors(now).couldNotTravel)
    }

    @Test
    fun theExcerptIsNamedByTheDecoderAndNotByWhatTheTagsClaim() = runBlocking {
        val store = DaoVectorStore(dao, { tracks }, model)
        // The file is 25 s of frames, so the middle ten begin at 7 s — whatever the tag reader
        // says. Naming it from the duration instead is how a correct vector comes to be refused:
        // against one phone that disagreed for two tracks in three.
        assertEquals(7_000L, store.excerptStartMs(tracks[0].path))
        assertEquals(98_000L, Excerpt.startMs(207_000))

        // A file that is not there has no window, and therefore nothing to say about it.
        assertEquals(null, store.excerptStartMs(File(baseDir, "music/нет такого.mp3").path))

        // For a format with no shared decoder the platform's duration is all there is — and those
        // vectors do not travel, so nothing rests on it.
        assertEquals(Excerpt.startMs(179_000), store.excerptStartMs(tracks[2].path))
    }

    @Test
    fun aVectorMadeByAnotherModelIsRefused() = runBlocking {
        analysed(0)
        coordinator(first).pushVectors(now)
        dao.clearAllEmbeddings()

        val day = File(folder, "aura/v1/$first/${VectorWriter.dayFile(now)}")
        day.writeText(day.readText().replace(model, "ff".repeat(32)))

        val pulled = coordinator(second).pullVectors(now)
        assertEquals(0, pulled.takenIn)
        assertEquals(mapOf(VectorVerdict.OtherModel to 1), pulled.refused)
        assertTrue(dao.getAllEmbeddings().isEmpty())
    }

    @Test
    fun aVectorFromAnotherAnalysisVersionIsRefused() = runBlocking {
        analysed(0)
        coordinator(first).pushVectors(now)
        dao.clearAllEmbeddings()

        val day = File(folder, "aura/v1/$first/${VectorWriter.dayFile(now)}")
        day.writeText(day.readText().replace("\"av\":$ANALYSIS_VERSION", "\"av\":${ANALYSIS_VERSION - 1}"))

        val pulled = coordinator(second).pullVectors(now)
        assertEquals(mapOf(VectorVerdict.OtherVersion to 1), pulled.refused)
    }

    @Test
    fun aRefusalOnOtherTermsIsReconsideredWhenTheTermsChange() {
        // The expensive case, and the one most likely to actually happen: this machine analyses
        // with the full export of the model and a phone defaults to the quantised one, so every
        // vector is refused. A refusal marks the batch read, so without this the day the owner
        // switches the phone over, a library's worth of work sitting right there would stay
        // unclaimed for good.
        runBlocking {
            analysed(0)
            coordinator(first).pushVectors(now)
            dao.clearAllEmbeddings()

            val otherModel = "ff".repeat(32)
            val refused = coordinator(second, analysingWith = otherModel).pullVectors(now)
            assertEquals(0, refused.takenIn)
            assertEquals(mapOf(VectorVerdict.OtherModel to 1), refused.refused)

            // The owner switches. Same device, same state file, terms that now agree.
            val taken = coordinator(second).pullVectors(now)
            assertEquals(1, taken.takenIn, "принято: $taken")
            assertEquals(1, dao.getAllEmbeddings().size)

            // And it is not read a third time now that the terms have settled.
            assertEquals(0, coordinator(second).pullVectors(now).takenIn)
        }
    }

    @Test
    fun aVectorTakenInIsOfferedOnwards() = runBlocking {
        // What makes the folder a cache a device can be restored from rather than one it can only
        // contribute to. Each device offers what it holds, so a vector ends up in the folder once
        // per device that has it — and the device that analysed it can lose it and get it back.
        analysed(0)
        assertEquals(1, coordinator(first).pushVectors(now).written)
        dao.clearAllEmbeddings()
        assertEquals(1, coordinator(second).pullVectors(now).takenIn)

        // The second device now has it and puts it in its own directory too.
        assertEquals(1, coordinator(second).pushVectors(now).written)
        val folder = FileSyncFolder(this@VectorRoundTripTest.folder, second)
        assertEquals(1, folder.files(second, VectorWriter.DIRECTORY).size)
        // And only once: offering it again every pass would have the folder grow without end.
        assertEquals(0, coordinator(second).pushVectors(now).written)
    }

    @Test
    fun aDeviceThatHasLostItsVectorsReadsTheFolderAgain() = runBlocking {
        analysed(0, 1)
        coordinator(first).pushVectors(now)
        dao.clearAllEmbeddings()
        assertEquals(2, coordinator(second).pullVectors(now).takenIn)

        // Everything gone but the state file, which says the folder has been read to the end. That
        // is the case the backup is for: a store wiped or a file that would not read, with the
        // vectors sitting right there in the folder.
        dao.clearAllEmbeddings()
        val back = coordinator(second).pullVectors(now)
        assertEquals(2, back.takenIn, "принято обратно: $back")
        assertEquals(2, dao.getAllEmbeddings().size)

        // And once they are back, the folder is not read again on every pass.
        assertEquals(0, coordinator(second).pullVectors(now).takenIn)
    }

    @Test
    fun aVectorAlreadyHeldIsNotTakenTwice() = runBlocking {
        analysed(0)
        coordinator(first).pushVectors(now)
        // Still analysed here: the vector on offer has nothing to add.
        val pulled = coordinator(second).pullVectors(now)
        assertEquals(0, pulled.takenIn)
        assertEquals(1, pulled.alreadyHeld)
        assertTrue(pulled.refused.isEmpty())
    }

    @Test
    fun aPassWithAnIncompleteLibraryRetiresNothingElse() = runBlocking {
        // The bug this is here for cost four thousand vectors on a phone. A pass whose library
        // lists only some of the tracks used to mark every other vector as dealt with — "nothing
        // to name it by" — and a vector marked once is never offered again. A library can be short
        // for ordinary reasons: not read yet, mid-rescan, a scanner catching up.
        analysed(0, 1)
        val partial = coordinator(first, library = tracks.take(1))
        assertEquals(1, partial.pushVectors(now, SyncCoordinator.PUBLISH_EVERYTHING).written)

        // The one it could not describe was left alone, so it goes out once the library is whole.
        assertEquals(1, coordinator(first).pushVectors(now).written)
        assertEquals(0, coordinator(first).pushVectors(now).written)
    }

    @Test
    fun aVectorForATrackThisLibraryDoesNotHoldWaitsUnclaimed() = runBlocking {
        analysed(0, 1)
        coordinator(first).pushVectors(now)
        dao.clearAllEmbeddings()

        val pulled = coordinator(second, library = tracks.take(1)).pullVectors(now)
        assertEquals(1, pulled.takenIn)
        assertEquals(1, pulled.unmatched)
    }

    @Test
    fun aVersionOneTableIsReadAndNothingInItCountsAsPublished() = runBlocking {
        // What is on a real machine right now: nine megabytes of analysis written before there was
        // anywhere to send it. Every row of it is therefore still to be offered.
        analysed(0, 1)
        dao.forceSave()
        val packed = File(baseDir, "data/embeddings.bin")
        assertTrue(packed.isFile)

        val reopened = DesktopMusicDao(File(baseDir, "data"))
        assertEquals(2, reopened.getAllEmbeddings().size)
        assertEquals(2, reopened.vectorsToPublish(100).size)

        reopened.markVectorsPublished(listOf(tracks[0].path), now)
        assertEquals(listOf(tracks[1].path), reopened.vectorsToPublish(100).map { it.path })
        // And the mark survives being written and read again.
        val again = DesktopMusicDao(File(baseDir, "data"))
        assertEquals(listOf(tracks[1].path), again.vectorsToPublish(100).map { it.path })
    }
}
