package music.ai.recommend.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exports the analysed library so smart-album clustering can be studied offline — DBSCAN's
 * behaviour depends entirely on the shape of the data. Opt-in:
 *
 *     am instrument -w -e bench true -e class music.ai.recommend.ai.ClusterExperimentTest ...
 */
@RunWith(AndroidJUnit4::class)
class ClusterExperimentTest {

    private val tag = "ClusterExp"

    /**
     * Writes the analysed library to the app's cache for offline analysis: one line per track,
     * tab-separated title, artist, folder, then the 512 floats. Read back with `run-as ... cat`.
     */
    @Test
    fun exportEmbeddings() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stored = runBlocking { EmbeddingStore.getInstance(context).all() }
        val songs = music.ai.recommend.scanner.MusicScanner().scanMusic(context)
            .flatMap { it.songs }.distinctBy { it.id }
            .filter { s -> stored[s.id]?.any { it != 0f } == true }
        val file = java.io.File(context.cacheDir, "embeddings.tsv")
        file.bufferedWriter().use { out ->
            for (song in songs) {
                fun clean(v: String) = v.replace('\t', ' ').replace('\n', ' ')
                out.write("${clean(song.title)}\t${clean(song.artist)}\t${clean(song.folderName)}\t")
                out.write(stored.getValue(song.id).joinToString(","))
                out.write("\n")
            }
        }
        Log.i(tag, "exported ${songs.size} tracks to ${file.absolutePath}")
    }

    /**
     * Writes the text embedding of every smart-album label to the cache as prompt\tfloats, so
     * naming can be tried against exported libraries offline (RealLibraryClusteringTest).
     */
    @Test
    fun exportLabelEmbeddings() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoder = ClapTextEncoder(ModelRepository.getInstance(context))
        assumeTrue("text model not on the device", !encoder.needsDownload())
        val file = java.io.File(context.cacheDir, "labels.tsv")
        try {
            file.bufferedWriter().use { out ->
                for (label in AlbumLabels.all) {
                    val v = runBlocking { encoder.encode(label.prompt) } ?: error("encoding failed")
                    out.write("${label.prompt}\t${context.getString(label.title)}\t${v.joinToString(",")}\n")
                }
            }
        } finally {
            encoder.release()
        }
        Log.i(tag, "exported ${AlbumLabels.all.size} labels to ${file.absolutePath}")
    }
}
