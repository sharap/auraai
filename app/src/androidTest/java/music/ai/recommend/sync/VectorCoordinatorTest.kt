package music.ai.recommend.sync

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import music.ai.recommend.ai.EmbeddingStore
import music.ai.recommend.ai.Excerpt
import music.ai.recommend.ai.ModelRepository
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Carrying embeddings, end to end, over real files from this phone's own library.
 *
 * Real files on purpose. Everything else about vectors is tested without a disk — the record, the
 * terms, the outbox, the reader — and what those cannot show is the one question only a file can
 * answer: whether the excerpt this device names for a track is the excerpt the analysis would have
 * used. That is asked of the container, through MediaExtractor, for each track in turn, and it is
 * the link where being nearly right is indistinguishable from being wrong.
 */
@RunWith(AndroidJUnit4::class)
class VectorCoordinatorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val embeddings get() = EmbeddingStore.getInstance(context)
    private val models get() = ModelRepository.getInstance(context)
    private val history get() = PlayHistory.getInstance(context)

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)
    private val now = 1_793_880_000_000L

    private lateinit var folder: File
    private lateinit var tracks: List<Song>

    @Before
    fun findSomeMp3s() {
        folder = File(context.cacheDir, "vector-round-trip").apply {
            deleteRecursively()
            mkdirs()
        }
        tracks = mp3sFromLibrary(3)
        assumeTrue("нужны mp3 в фонотеке", tracks.size == 3)
        runBlocking { clearSeeded() }
    }

    @After
    fun tidyUp() {
        folder.deleteRecursively()
        runBlocking { clearSeeded() }
    }

    /**
     * Only the rows this test made; the device's own analysis, if any, is left alone.
     *
     * Through the store and not through SQL: the store keeps the vectors in memory for search and
     * the wave, and a row deleted behind its back stays analysed as far as they are concerned —
     * which is exactly how the first two versions of this test lied to themselves.
     */
    private suspend fun clearSeeded() {
        embeddings.remove(tracks.map { it.id })
    }

    private fun mp3sFromLibrary(wanted: Int): List<Song> {
        val found = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
            ),
            "${MediaStore.Audio.Media.DATA} LIKE '%.mp3' AND ${MediaStore.Audio.Media.DURATION} > 60000",
            null,
            "${MediaStore.Audio.Media._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && found.size < wanted) {
                found += Song(
                    id = cursor.getLong(0),
                    title = cursor.getString(2).orEmpty(),
                    artist = cursor.getString(3).orEmpty(),
                    album = "",
                    albumId = 0,
                    duration = cursor.getLong(4),
                    uri = Uri.EMPTY,
                    path = cursor.getString(1),
                    folderName = "music"
                )
            }
        }
        return found
    }

    private fun vector(seed: Int) = FloatArray(EmbeddingStore.DIMENSIONS) {
        ((it + seed) % 17) * 0.05f + 0.013f
    }

    private fun coordinator(deviceId: String, library: List<Song> = tracks): SyncCoordinator<Long> {
        val state = SyncState.load(File(folder, "state-$deviceId.json"))
        val sink = SafSyncFolder.fromDirectory(context, folder, deviceId)
        return SyncCoordinator(
            deviceId = deviceId,
            state = state,
            store = PlayHistoryStore(history) { library },
            folder = { sink },
            vectors = EmbeddingVectorStore(embeddings, models) { library }
        )
    }

    @Test
    fun aVectorCrossesTheFolderAndComesBackTheSame() = runBlocking {
        for ((index, song) in tracks.withIndex()) embeddings.put(song.id, vector(index))

        val pushed = coordinator(first).pushVectors(now)
        assertEquals("отправлено: $pushed", 3, pushed.written)
        assertEquals(0, pushed.couldNotTravel)
        assertEquals(0, pushed.gone)

        // As the other device: the same library, none of it analysed.
        clearSeeded()
        assertTrue(tracks.none { it.id in embeddings.ids() })

        val pulled = coordinator(second).pullVectors(now)
        assertEquals("принято: $pulled", 3, pulled.takenIn)
        assertTrue("отказы: ${pulled.refused}", pulled.refused.isEmpty())
        assertEquals(0, pulled.unmatched)

        // Bit for bit, which is the whole point: a vector that arrives slightly changed is a track
        // standing slightly in the wrong place, and nothing would say so.
        val held = embeddings.all()
        for ((index, song) in tracks.withIndex()) {
            assertTrue("вектор ${song.path} не совпал", vector(index).contentEquals(held[song.id]))
        }
    }

    @Test
    fun theExcerptThisDeviceNamesIsTheOneItsAnalysisWouldUse() = runBlocking {
        // The link that only a real file can check. The name travels with the vector, and it has to
        // be the container's reading rather than MediaStore's: the two differ by about a
        // millisecond, which crosses one of the two-second buckets once in two thousand tracks.
        val store = EmbeddingVectorStore(embeddings, models) { tracks }
        for (song in tracks) {
            val named = store.excerptStartMs(song.id)
            assertTrue("нет окна для ${song.path}", named != null)
            assertEquals("это должно быть кратно секунде", 0L, named!! % 1000)
            // Within one bucket of what the key's duration would have suggested, which is the most
            // that may be assumed about the two readings agreeing.
            val fromKey = Excerpt.startMs(song.duration)
            assertTrue(
                "окно ${song.path}: контейнер $named, MediaStore $fromKey",
                kotlin.math.abs(named - fromKey) <= 2000
            )
        }
    }

    @Test
    fun aVectorNamingAnotherExcerptIsRefused() = runBlocking {
        for ((index, song) in tracks.withIndex()) embeddings.put(song.id, vector(index))
        coordinator(first).pushVectors(now)
        clearSeeded()

        // The same folder, with every start moved by two seconds — one bucket, the smallest
        // disagreement the rule can have. A cosine at that distance is about 0.95 where 0.9985 is
        // the bar, so this is the difference between a vector and a wrong vector.
        val day = File(folder, "aura/v1/$first/${VectorWriter.dayFile(now)}")
        val moved = day.readLines().map { line ->
            line.replace(Regex("\"s\":(\\d+)")) { "\"s\":" + (it.groupValues[1].toLong() + 2000) }
        }
        day.writeText(moved.joinToString("\n", postfix = "\n"))

        val pulled = coordinator(second).pullVectors(now)
        assertEquals(0, pulled.takenIn)
        assertEquals(mapOf(VectorVerdict.OtherExcerpt to 3), pulled.refused)
        assertTrue(tracks.none { it.id in embeddings.ids() })
    }

    @Test
    fun aVectorIsOfferedOnceAndThenLetAlone() = runBlocking {
        embeddings.put(tracks[0].id, vector(0))
        val sender = coordinator(first)
        assertEquals(1, sender.pushVectors(now).written)
        // Marked as dealt with, so the next pass has nothing to say — a library of four thousand
        // would otherwise be rewritten into the folder on every pass.
        assertEquals(0, coordinator(first).pushVectors(now).written)
    }

    @Test
    fun aVectorForATrackThisLibraryDoesNotHoldWaitsUnclaimed() = runBlocking {
        for ((index, song) in tracks.withIndex()) embeddings.put(song.id, vector(index))
        coordinator(first).pushVectors(now)
        clearSeeded()

        // The other device knows about one of the three.
        val pulled = coordinator(second, library = tracks.take(1)).pullVectors(now)
        assertEquals(1, pulled.takenIn)
        assertEquals(2, pulled.unmatched)
    }
}
