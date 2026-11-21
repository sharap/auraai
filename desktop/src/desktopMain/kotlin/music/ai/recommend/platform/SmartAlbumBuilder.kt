package music.ai.recommend.platform

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.ai.recommend.ai.AlbumLabels
import music.ai.recommend.ai.SmartAlbum
import music.ai.recommend.ai.SmartAlbumClustering
import music.ai.recommend.ai.SmartAlbumNaming
import music.ai.recommend.db.ANALYSIS_VERSION
import music.ai.recommend.db.getAppDatabase
import music.ai.recommend.model.Song
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

actual class SmartAlbumBuilder actual constructor(private val textEncoder: ClapTextEncoder) {

    private val db = getAppDatabase()
    private val gson = Gson()
    private val baseDir = File(System.getProperty("user.home"), ".aimusic")
    private val albumsFile get() = File(baseDir, "smart_albums.json")
    private val labelsFile get() = File(baseDir, "smart_album_labels.bin")

    private class Stored(
        val signature: String = "",
        val named: Boolean = false,
        val albums: List<StoredAlbum>? = null
    )

    private class StoredAlbum(val label: Int = UNNAMED, val paths: List<String>? = null)

    actual suspend fun albums(
        library: List<Song>,
        epsScale: Float,
        rebuild: Boolean
    ): List<SmartAlbum> = withContext(Dispatchers.Default) {
        // Undecodable tracks are stored as zero vectors so the scan does not retry them forever;
        // they carry no direction, so leaving them in would put every one of them at distance 1
        // from everything and drag the eps sweep around.
        val vectors = db.musicDao().getAllEmbeddings()
            .filter { entity -> entity.vector.size == EMBEDDING_SIZE && entity.vector.any { it != 0f } }
            .associate { entity -> entity.path to FloatArray(entity.vector.size) { entity.vector[it] } }

        val songs = library.filter { it.path in vectors }.distinctBy { it.path }
        if (songs.size < 2 * SmartAlbumClustering.MIN_ALBUM_SIZE) return@withContext emptyList()

        val signature = SmartAlbumNaming.signature(songs.map { it.path }, ANALYSIS_VERSION, epsScale)
        val cached = if (rebuild) null else load()
        // An unnamed result is only good until the text model can be reached.
        val stored = if (cached != null && cached.signature == signature && cached.named) {
            cached
        } else {
            build(songs, vectors, epsScale, signature).also(::save)
        }
        present(stored, songs)
    }

    private suspend fun build(
        songs: List<Song>,
        vectors: Map<String, FloatArray>,
        epsScale: Float,
        signature: String
    ): Stored {
        val start = System.nanoTime()
        val points = songs.map { vectors.getValue(it.path) }
        val clusters = SmartAlbumClustering.cluster(points, epsScale = epsScale)

        val labelEmbeddings = labelEmbeddings()
        val labels = if (labelEmbeddings == null || clusters.isEmpty()) {
            IntArray(clusters.size) { UNNAMED }
        } else {
            val libraryCentroid = SmartAlbumClustering.centroid(points, IntArray(points.size) { it })
            SmartAlbumNaming.bestLabels(
                clusters.map { SmartAlbumClustering.centroid(points, it) },
                libraryCentroid,
                labelEmbeddings
            )
        }
        println("SmartAlbums: ${songs.size} tracks -> ${clusters.size} albums in ${(System.nanoTime() - start) / 1_000_000} ms")
        return Stored(
            signature = signature,
            named = labelEmbeddings != null,
            albums = clusters.mapIndexed { i, members ->
                StoredAlbum(labels[i], members.map { songs[it].path })
            }
        )
    }

    private fun present(stored: Stored, songs: List<Song>): List<SmartAlbum> {
        val byPath = songs.associateBy { it.path }
        val albums = stored.albums.orEmpty()
            .map { album -> album to album.paths.orEmpty().mapNotNull { byPath[it] } }
            .filter { (_, albumSongs) -> albumSongs.isNotEmpty() }

        val baseTitles = albums.mapIndexed { i, (album, _) ->
            AlbumLabels.all.getOrNull(album.label)?.title ?: "Mix ${i + 1}"
        }
        val titles = SmartAlbumNaming.uniqueTitles(
            baseTitles,
            albums.map { (_, albumSongs) -> SmartAlbumNaming.dominantArtist(albumSongs.map { it.artist to it.title }) }
        )
        return albums.mapIndexed { i, (album, albumSongs) ->
            SmartAlbum(
                id = SmartAlbumNaming.albumId(album.paths.orEmpty()),
                title = titles[i],
                subtitle = SmartAlbumNaming.leadingArtists(albumSongs),
                songs = albumSongs
            )
        }
    }

    /**
     * Text embeddings of the vocabulary: from disk, or encoded once. Thirty-five prompts through
     * the text model is a few seconds, and the result only changes when the vocabulary does.
     */
    private suspend fun labelEmbeddings(): List<FloatArray>? {
        readLabels()?.let { return it }
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
            if (!baseDir.exists()) baseDir.mkdirs()
            DataOutputStream(labelsFile.outputStream().buffered()).use { out ->
                out.writeInt(AlbumLabels.fingerprint)
                out.writeInt(vectors.size)
                for (v in vectors) {
                    out.writeInt(v.size)
                    for (x in v) out.writeFloat(x)
                }
            }
        }.onFailure { println("SmartAlbums: could not cache label embeddings: ${it.message}") }
    }

    private fun load(): Stored? = runCatching {
        albumsFile.takeIf { it.exists() }?.reader()?.use { gson.fromJson(it, Stored::class.java) }
    }.getOrNull()

    private fun save(stored: Stored) {
        runCatching {
            if (!baseDir.exists()) baseDir.mkdirs()
            // Written aside and renamed, so a crash mid-write cannot leave a half-parsed cache.
            val tmp = File(albumsFile.path + ".tmp")
            tmp.writer().use { gson.toJson(stored, it) }
            if (!tmp.renameTo(albumsFile)) {
                albumsFile.delete()
                tmp.renameTo(albumsFile)
            }
        }.onFailure { println("SmartAlbums: could not cache albums: ${it.message}") }
    }

    private companion object {
        const val UNNAMED = -1
        const val EMBEDDING_SIZE = 512
    }
}
