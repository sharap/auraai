package music.ai.recommend.ai

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.ai.recommend.R
import music.ai.recommend.strings.resources.Res
import music.ai.recommend.strings.resources.allStringResources
import music.ai.recommend.strings.resources.smart_album_untitled
import org.jetbrains.compose.resources.getString
import music.ai.recommend.model.Song
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File


/**
 * Builds smart albums from the analysed library and keeps the last result on disk.
 *
 * Clustering takes seconds on a large library and naming needs the 126 MB text model, so neither
 * should run on every launch: the result is cached under a signature of the analysed tracks and
 * the label text embeddings are cached separately, which means the text model is only ever
 * loaded once per vocabulary.
 */
class SmartAlbumBuilder(
    private val context: Context,
    private val models: ModelRepository,
    private val embeddings: EmbeddingStore,
    private val textEncoder: ClapTextEncoder
) {
    private val gson = Gson()
    private val albumsFile get() = File(context.filesDir, "smart_albums.json")
    private val labelsFile get() = File(context.filesDir, "smart_album_labels.bin")

    private class Stored(val signature: String, val named: Boolean, val albums: List<StoredAlbum>)
    private class StoredAlbum(val label: Int, val songIds: LongArray)

    /**
     * Albums for [library], from the cache when it is still current.
     *
     * @param epsScale the user's multiplier on the automatically chosen eps, see [SmartAlbumClustering.cluster].
     * @param rebuild ignore the cache even if it matches.
     */
    suspend fun albums(
        library: List<Song>,
        epsScale: Float = 1f,
        rebuild: Boolean = false
    ): List<SmartAlbum> = withContext(Dispatchers.Default) {
        val vectors = embeddings.all()
        val songs = library.filter { it.id in vectors }.distinctBy { it.id }
        if (songs.size < 2 * SmartAlbumClustering.MIN_ALBUM_SIZE) return@withContext emptyList()

        val signature = SmartAlbumNaming.signature(songs.map { it.id }, models.embeddingsVariant.value?.name, epsScale)
        val cached = if (rebuild) null else load()
        // An unnamed result is only good until the text model shows up.
        val stored = if (cached != null && cached.signature == signature && (cached.named || textEncoder.needsDownload())) {
            cached
        } else {
            build(songs, vectors, epsScale, signature).also(::save)
        }
        present(stored, songs)
    }

    private suspend fun build(
        songs: List<Song>,
        vectors: Map<Long, FloatArray>,
        epsScale: Float,
        signature: String
    ): Stored {
        val start = System.nanoTime()
        val points = songs.map { vectors.getValue(it.id) }
        val clusters = SmartAlbumClustering.cluster(points, epsScale = epsScale)

        val labelEmbeddings = labelEmbeddings()
        val labels = if (labelEmbeddings == null) {
            IntArray(clusters.size) { UNNAMED }
        } else {
            val library = SmartAlbumClustering.centroid(points, IntArray(points.size) { it })
            SmartAlbumNaming.bestLabels(clusters.map { SmartAlbumClustering.centroid(points, it) }, library, labelEmbeddings)
        }
        Log.d(TAG, "${songs.size} tracks -> ${clusters.size} albums in ${(System.nanoTime() - start) / 1_000_000} ms")
        return Stored(
            signature = signature,
            named = labelEmbeddings != null,
            albums = clusters.mapIndexed { i, members ->
                StoredAlbum(labels[i], LongArray(members.size) { songs[members[it]].id })
            }
        )
    }

    /**
     * The album's name in the owner's language, out of the strings both interfaces read.
     *
     * Suspending, which is what reading a Compose resource outside composition is. The caller was
     * already suspending, so this cost nothing; what it bought is that the phone and the desktop
     * can no longer disagree about what an album is called.
     */
    private suspend fun nameOf(key: String): String? =
        Res.allStringResources[key]?.let { getString(it) }

    private suspend fun present(stored: Stored, songs: List<Song>): List<SmartAlbum> {
        val byId = songs.associateBy { it.id }
        val albums = stored.albums.map { album -> album to album.songIds.asList().mapNotNull { byId[it] } }
        val baseTitles = albums.mapIndexed { i, (album, _) ->
            AlbumLabels.all.getOrNull(album.label)?.let { nameOf(it.nameKey) }
                ?: getString(Res.string.smart_album_untitled, i + 1)
        }
        val titles = SmartAlbumNaming.uniqueTitles(
            baseTitles,
            albums.map { (_, albumSongs) -> SmartAlbumNaming.dominantArtist(albumSongs.map { it.artist to it.title }) }
        )
        return albums.mapIndexed { i, (album, albumSongs) ->
            SmartAlbum(
                id = SmartAlbumNaming.albumId(album.songIds.asList()),
                title = titles[i],
                subtitle = SmartAlbumNaming.leadingArtists(albumSongs),
                songs = albumSongs
            )
        }
    }

    /**
     * Text embeddings of the vocabulary: from disk, or encoded once if the text model is already
     * here. Never starts a download on its own — the albums are still useful unnamed.
     */
    private suspend fun labelEmbeddings(): List<FloatArray>? {
        readLabels()?.let { return it }
        if (textEncoder.needsDownload()) return null
        val encoded = AlbumLabels.all.map { textEncoder.encode(it.prompt) ?: return null }
        writeLabels(encoded)
        return encoded
    }

    private fun readLabels(): List<FloatArray>? = runCatching {
        DataInputStream(labelsFile.inputStream().buffered()).use { input ->
            if (input.readInt() != AlbumLabels.fingerprint) return null
            val count = input.readInt()
            if (count != AlbumLabels.all.size) return null
            List(count) { FloatArray(input.readInt()) { input.readFloat() } }
        }
    }.getOrNull()

    private fun writeLabels(vectors: List<FloatArray>) {
        runCatching {
            DataOutputStream(labelsFile.outputStream().buffered()).use { out ->
                out.writeInt(AlbumLabels.fingerprint)
                out.writeInt(vectors.size)
                for (v in vectors) {
                    out.writeInt(v.size)
                    for (x in v) out.writeFloat(x)
                }
            }
        }.onFailure { Log.w(TAG, "Could not cache label embeddings", it) }
    }

    private fun load(): Stored? = runCatching {
        albumsFile.takeIf { it.exists() }?.reader()?.use { gson.fromJson(it, Stored::class.java) }
    }.getOrNull()

    private fun save(stored: Stored) {
        runCatching {
            val tmp = File(albumsFile.path + ".tmp")
            tmp.writer().use { gson.toJson(stored, it) }
            tmp.renameTo(albumsFile)
        }.onFailure { Log.w(TAG, "Could not cache smart albums", it) }
    }

    private companion object {
        const val TAG = "SmartAlbums"
        const val UNNAMED = -1
    }
}
