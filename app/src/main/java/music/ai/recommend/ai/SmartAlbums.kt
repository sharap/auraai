package music.ai.recommend.ai

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.ai.recommend.R
import music.ai.recommend.model.Song
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** An album the library was grouped into by sound rather than by tags. */
@Immutable
data class SmartAlbum(
    /** Stable for the same set of tracks, so navigation survives a rebuild that changes nothing. */
    val id: String,
    val title: String,
    /** The artists that dominate the album, which also tells apart two albums with one label. */
    val subtitle: String,
    val songs: List<Song>
)

/**
 * A description CLAP scores an album against, and what to call the album if it wins.
 *
 * Prompts are English because the text encoder was trained on English captions; they describe
 * the sound, not the market category, since that is what an audio embedding can actually match.
 */
internal data class AlbumLabel(val prompt: String, @StringRes val title: Int)

internal object AlbumLabels {
    val all = listOf(
        AlbumLabel("trance music with euphoric synth leads", R.string.smart_label_trance),
        AlbumLabel("house music with a four-on-the-floor beat", R.string.smart_label_house),
        AlbumLabel("big room electronic dance music drops", R.string.smart_label_edm),
        AlbumLabel("drum and bass with fast breakbeats", R.string.smart_label_dnb),
        AlbumLabel("catchy pop song with female vocals", R.string.smart_label_pop),
        AlbumLabel("upbeat dance pop remix", R.string.smart_label_dance_pop),
        AlbumLabel("russian pop ballad with male vocals", R.string.smart_label_estrada),
        AlbumLabel("russian chanson with guitar and male vocals", R.string.smart_label_chanson),
        AlbumLabel("rock band with electric guitars and drums", R.string.smart_label_rock),
        AlbumLabel("heavy metal with distorted guitars", R.string.smart_label_metal),
        AlbumLabel("instrumental post-rock with swelling guitars", R.string.smart_label_post_rock),
        AlbumLabel("indie folk with acoustic guitar and soft vocals", R.string.smart_label_indie_folk),
        AlbumLabel("medieval folk music with flutes and fiddles", R.string.smart_label_folk),
        AlbumLabel("celtic harp music", R.string.smart_label_harp),
        AlbumLabel("nordic folk with ethereal female vocals", R.string.smart_label_nordic),
        AlbumLabel("traditional folk dance music", R.string.smart_label_folk_dance),
        AlbumLabel("classical orchestra symphony", R.string.smart_label_classical),
        AlbumLabel("solo piano classical piece", R.string.smart_label_piano),
        AlbumLabel("romantic piano and strings", R.string.smart_label_romantic),
        AlbumLabel("opera singing with orchestra", R.string.smart_label_opera),
        AlbumLabel("sacred choir singing a cappella", R.string.smart_label_choir),
        AlbumLabel("epic cinematic orchestral trailer music", R.string.smart_label_epic),
        AlbumLabel("film soundtrack score", R.string.smart_label_soundtrack),
        AlbumLabel("calm ambient soundscape", R.string.smart_label_ambient),
        AlbumLabel("relaxing new age music", R.string.smart_label_new_age),
        AlbumLabel("spanish flamenco guitar", R.string.smart_label_flamenco),
        AlbumLabel("latin reggaeton party music", R.string.smart_label_latin),
        AlbumLabel("jazz with saxophone", R.string.smart_label_jazz),
        AlbumLabel("blues guitar", R.string.smart_label_blues),
        AlbumLabel("hip hop beat with rap vocals", R.string.smart_label_hip_hop),
        AlbumLabel("duduk and wind instruments playing a sad melody", R.string.smart_label_winds),
        AlbumLabel("birds singing in nature", R.string.smart_label_nature),
        AlbumLabel("dark gothic music", R.string.smart_label_gothic),
        AlbumLabel("eighties synth pop", R.string.smart_label_synth_pop),
        AlbumLabel("lo-fi chill beats", R.string.smart_label_lofi)
    )

    /** Changes whenever the vocabulary does, invalidating cached label embeddings and names. */
    val fingerprint: Int = all.joinToString("|") { it.prompt }.hashCode()
}

/**
 * Pure pieces of turning clusters into named albums.
 *
 * Naming uses CLAP zero-shot: audio and text embeddings share one space, so an album's mean audio
 * embedding can be compared with descriptions of genres and moods. Scored raw, albums lean toward
 * whatever label sits nearest "music in general" — the same shared component that made raw
 * clustering fail — so part of each label's fit to the library as a whole is subtracted.
 */
object SmartAlbumNaming {

    /**
     * @param albumCentroids unit-length mean embedding per album.
     * @param libraryCentroid unit-length mean embedding of every analysed track.
     * @param labelEmbeddings unit-length text embedding per label.
     * @return the winning label index per album.
     *
     * Subtracting the whole library baseline overcorrects: in a library that is mostly trance,
     * "trance" fits the library as well as it fits any album, cancels out, and the trance albums
     * end up named after whatever is left. Half the baseline kept the dominant genre while still
     * separating the albums, on both a 161-track EDM library and a 4308-track mixed one.
     */
    internal fun bestLabels(
        albumCentroids: List<FloatArray>,
        libraryCentroid: FloatArray,
        labelEmbeddings: List<FloatArray>
    ): IntArray {
        val baseline = FloatArray(labelEmbeddings.size) { SmartAlbumClustering.dot(libraryCentroid, labelEmbeddings[it]) }
        return IntArray(albumCentroids.size) { a ->
            var best = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (l in labelEmbeddings.indices) {
                val score = SmartAlbumClustering.dot(albumCentroids[a], labelEmbeddings[l]) - BASELINE_WEIGHT * baseline[l]
                if (score > bestScore) {
                    bestScore = score
                    best = l
                }
            }
            best
        }
    }

    /**
     * Titles for albums in size order. The first album with a label keeps it plain; a repeat is
     * told apart by the artist that dominates it ("Классика · Secret Garden") and, failing that,
     * by a number ("Классика 2").
     *
     * @param artists the dominant artist per album, or null when no one artist does.
     */
    internal fun uniqueTitles(titles: List<String>, artists: List<String?> = List(titles.size) { null }): List<String> {
        val result = MutableList(titles.size) { "" }
        val taken = HashSet<String>()
        val numbered = HashMap<String, Int>()
        for (i in titles.indices) {
            val title = titles[i]
            val candidate = when {
                title !in taken -> title
                artists[i] != null && "$title · ${artists[i]}" !in taken -> "$title · ${artists[i]}"
                else -> {
                    var n = numbered[title] ?: 1
                    do n++ while ("$title $n" in taken)
                    numbered[title] = n
                    "$title $n"
                }
            }
            taken += candidate
            result[i] = candidate
        }
        return result
    }

    /** The artist of at least half the album, if there is one. */
    internal fun dominantArtist(artistsAndTitles: List<Pair<String, String>>): String? {
        val top = artistsAndTitles.mapNotNull { (artist, title) -> artistOf(artist, title) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return top.key.takeIf { top.value * 2 >= artistsAndTitles.size }
    }

    /**
     * The artists that make up most of an album. Many files carry no artist tag but name one in
     * the title ("Валерий Меладзе - Небеса"), so that is used as a fallback.
     */
    internal fun leadingArtists(songs: List<Song>, limit: Int = 3): String =
        leadingArtistsOf(songs.map { it.artist to it.title }, limit)

    /** [leadingArtists] over (artist tag, title) pairs. */
    internal fun leadingArtistsOf(artistsAndTitles: List<Pair<String, String>>, limit: Int = 3): String =
        artistsAndTitles.mapNotNull { (artist, title) -> artistOf(artist, title) }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit)
            .joinToString(", ") { it.key }

    internal fun artistOf(artist: String, title: String): String? {
        val tagged = artist.trim()
        if (!tagged.equals("<unknown>", ignoreCase = true) && tagged.any { it.isLetter() }) return tagged
        val dash = title.indexOf(" - ")
        if (dash > 0) {
            val candidate = title.substring(0, dash).trimStart { it.isDigit() || it == '.' || it == ' ' }.trim()
            if (candidate.length in 2..40 && candidate.any { it.isLetter() }) return candidate
        }
        return null
    }

    private const val BASELINE_WEIGHT = 0.5f

    /** Order-independent id for a set of songs. */
    internal fun albumId(songIds: LongArray): String =
        songIds.sorted().joinToString(",").hashCode().toUInt().toString(16)

    /** Identifies what the albums were built from; any change means they must be rebuilt. */
    internal fun signature(songIds: Collection<Long>, variant: String?, epsScale: Float = 1f): String {
        var hash = 1L
        for (id in songIds.sorted()) hash = hash * 1_000_003L + id
        return "$variant:${AlbumLabels.fingerprint}:${"%.2f".format(java.util.Locale.ROOT, epsScale)}:${songIds.size}:$hash"
    }
}

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

    private fun present(stored: Stored, songs: List<Song>): List<SmartAlbum> {
        val byId = songs.associateBy { it.id }
        val albums = stored.albums.map { album -> album to album.songIds.asList().mapNotNull { byId[it] } }
        val baseTitles = albums.mapIndexed { i, (album, _) ->
            AlbumLabels.all.getOrNull(album.label)?.let { context.getString(it.title) }
                ?: context.getString(R.string.smart_album_untitled, i + 1)
        }
        val titles = SmartAlbumNaming.uniqueTitles(
            baseTitles,
            albums.map { (_, albumSongs) -> SmartAlbumNaming.dominantArtist(albumSongs.map { it.artist to it.title }) }
        )
        return albums.mapIndexed { i, (album, albumSongs) ->
            SmartAlbum(
                id = SmartAlbumNaming.albumId(album.songIds),
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
