package music.ai.recommend.ai

import androidx.compose.runtime.Immutable
import music.ai.recommend.model.Song
import kotlin.math.roundToInt

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
internal data class AlbumLabel(val prompt: String, val title: String)

internal object AlbumLabels {
    val all = listOf(
        AlbumLabel("trance music with euphoric synth leads", "Trance"),
        AlbumLabel("house music with a four-on-the-floor beat", "House"),
        AlbumLabel("big room electronic dance music drops", "EDM"),
        AlbumLabel("drum and bass with fast breakbeats", "Drum & Bass"),
        AlbumLabel("catchy pop song with female vocals", "Pop"),
        AlbumLabel("upbeat dance pop remix", "Dance Pop"),
        AlbumLabel("russian pop ballad with male vocals", "Pop Ballads"),
        AlbumLabel("russian chanson with guitar and male vocals", "Chanson"),
        AlbumLabel("rock band with electric guitars and drums", "Rock"),
        AlbumLabel("heavy metal with distorted guitars", "Heavy Rock"),
        AlbumLabel("instrumental post-rock with swelling guitars", "Post-Rock"),
        AlbumLabel("indie folk with acoustic guitar and soft vocals", "Indie Folk"),
        AlbumLabel("medieval folk music with flutes and fiddles", "Folk"),
        AlbumLabel("celtic harp music", "Celtic Harp"),
        AlbumLabel("nordic folk with ethereal female vocals", "Nordic Folk"),
        AlbumLabel("traditional folk dance music", "Folk Dances"),
        AlbumLabel("classical orchestra symphony", "Classical"),
        AlbumLabel("solo piano classical piece", "Piano"),
        AlbumLabel("romantic piano and strings", "Romantic"),
        AlbumLabel("opera singing with orchestra", "Opera"),
        AlbumLabel("sacred choir singing a cappella", "Choir"),
        AlbumLabel("epic cinematic orchestral trailer music", "Epic"),
        AlbumLabel("film soundtrack score", "Soundtracks"),
        AlbumLabel("calm ambient soundscape", "Ambient"),
        AlbumLabel("relaxing new age music", "New Age"),
        AlbumLabel("spanish flamenco guitar", "Spanish Guitar"),
        AlbumLabel("latin reggaeton party music", "Latin"),
        AlbumLabel("jazz with saxophone", "Jazz"),
        AlbumLabel("blues guitar", "Blues"),
        AlbumLabel("hip hop beat with rap vocals", "Hip-Hop"),
        AlbumLabel("duduk and wind instruments playing a sad melody", "Winds"),
        AlbumLabel("birds singing in nature", "Nature Sounds"),
        AlbumLabel("dark gothic music", "Gothic"),
        AlbumLabel("eighties synth pop", "Synth-Pop"),
        AlbumLabel("lo-fi chill beats", "Lo-Fi")
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
 *
 * Tracks are identified by path, which is what the embeddings table is keyed by. Song.id on this
 * platform is only a hash of the path, so routing through it would add collisions for nothing.
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
     * told apart by the artist that dominates it ("Classical · Secret Garden") and, failing that,
     * by a number ("Classical 2").
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
        if (!tagged.equals("<unknown>", ignoreCase = true) &&
            !tagged.equals("Unknown Artist", ignoreCase = true) &&
            !tagged.equals("Unknown", ignoreCase = true) &&
            tagged.any { it.isLetter() }
        ) return tagged
        val dash = title.indexOf(" - ")
        if (dash > 0) {
            val candidate = title.substring(0, dash).trimStart { it.isDigit() || it == '.' || it == ' ' }.trim()
            if (candidate.length in 2..40 && candidate.any { it.isLetter() }) return candidate
        }
        return null
    }

    private const val BASELINE_WEIGHT = 0.5f

    /** Order-independent id for a set of tracks. */
    internal fun albumId(paths: List<String>): String =
        paths.sorted().joinToString(",").hashCode().toUInt().toString(16)

    /**
     * Identifies what the albums were built from; any change means they must be rebuilt.
     *
     * eps is folded in as a rounded integer rather than a formatted decimal, so the signature does
     * not depend on the machine's locale.
     */
    internal fun signature(paths: Collection<String>, analysisVersion: Int, epsScale: Float = 1f): String {
        var hash = 1L
        for (path in paths.sorted()) hash = hash * 1_000_003L + path.hashCode()
        return "$analysisVersion:${AlbumLabels.fingerprint}:${(epsScale * 100).roundToInt()}:${paths.size}:$hash"
    }
}
