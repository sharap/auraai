package music.ai.recommend.sync

import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import music.ai.recommend.ai.EmbeddingStore
import music.ai.recommend.ai.ModelRepository
import music.ai.recommend.ai.Mp3Excerpt
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.model.Song
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Hand-run: what would this device's whole library offer, and what would it hold back?
 *
 * Into a folder of its own, so nothing reaches the real one. It exists because a pass reports three
 * numbers — written, cannot travel, nothing to name them by — and on the real exchange the third
 * one swallowed four thousand vectors without anybody being able to say why. Run it, read the log,
 * and then run `VectorMigrationTest#offerEverythingAgain`, because this marks what it offered.
 */
@RunWith(AndroidJUnit4::class)
class PushDiagnosisTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun whatTheWholeLibraryWouldOffer(): Unit = runBlocking {
        val embeddings = EmbeddingStore.getInstance(context)
        val library = wholeLibrary()
        assumeTrue("нет фонотеки", library.size > 100)
        assumeTrue("нет векторов", embeddings.ids().isNotEmpty())

        val folder = File(context.cacheDir, "push-diagnosis").apply {
            deleteRecursively()
            mkdirs()
        }
        val state = SyncState.load(File(folder, "state.json"))
        val device = "cc".repeat(16)
        val store = EmbeddingVectorStore(embeddings, ModelRepository.getInstance(context)) { library }

        // What the store itself says about the rows waiting, before any of it is offered.
        val waiting = store.toPublish(200)
        val byId = library.associateBy { it.id }
        var noTrack = 0
        var noWindow = 0
        var notMp3 = 0
        val examples = StringBuilder()
        for (held in waiting) {
            val song = byId[held.track]
            if (song == null) {
                noTrack++
                if (examples.length < 400) examples.append("\n  нет трека ${held.track}")
                continue
            }
            if (!Mp3Excerpt.handles(song.path)) {
                notMp3++
                continue
            }
            if (Mp3Excerpt.startMs(File(song.path)) == null) {
                noWindow++
                if (examples.length < 400) examples.append("\n  нет окна ${song.path}")
            }
        }
        Log.i(
            TAG,
            "из ${waiting.size} ждущих: нет трека в фонотеке $noTrack, не mp3 $notMp3, " +
                "нет окна $noWindow$examples"
        )

        val coordinator = SyncCoordinator(
            deviceId = device,
            state = state,
            store = PlayHistoryStore(PlayHistory.getInstance(context)) { library },
            folder = { SafSyncFolder.fromDirectory(context, folder, device) },
            vectors = store
        )
        val pushed = coordinator.pushVectors(System.currentTimeMillis(), SyncCoordinator.PUBLISH_EVERYTHING)
        val written = File(folder, "aura/v1/$device/${VectorWriter.DIRECTORY}")
            .listFiles()?.sumOf { it.length() } ?: 0
        Log.i(TAG, "отправка: $pushed, на диске $written байт, фонотека ${library.size}, " +
            "векторов ${embeddings.ids().size}")
        folder.deleteRecursively()
    }

    private fun wholeLibrary(): List<Song> {
        val songs = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
            ),
            null,
            null,
            "${MediaStore.Audio.Media._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                songs += Song(
                    id = cursor.getLong(0),
                    title = cursor.getString(2).orEmpty(),
                    artist = cursor.getString(3).orEmpty(),
                    album = "",
                    albumId = 0,
                    duration = cursor.getLong(4),
                    uri = Uri.EMPTY,
                    path = cursor.getString(1).orEmpty(),
                    folderName = ""
                )
            }
        }
        return songs
    }

    private companion object {
        const val TAG = "PushDiagnosisTest"
    }
}
