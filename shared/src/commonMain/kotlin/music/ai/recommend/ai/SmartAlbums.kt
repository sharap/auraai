package music.ai.recommend.ai

import music.ai.recommend.model.Song
import kotlin.math.roundToInt

/** An album the library was grouped into by sound rather than by tags. */
// Declared stable to both Compose compilers in compose-stability.conf at the repository root,
// rather than annotated @Immutable here: this module has no Compose dependency, and could not
// annotate a type it shares with two interfaces even if it wanted to.
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
    fun bestLabels(
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
    fun uniqueTitles(titles: List<String>, artists: List<String?> = List(titles.size) { null }): List<String> {
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
    fun dominantArtist(artistsAndTitles: List<Pair<String, String>>): String? {
        val top = artistsAndTitles.mapNotNull { (artist, title) -> artistOf(artist, title) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return top.key.takeIf { top.value * 2 >= artistsAndTitles.size }
    }

    /**
     * The artists that make up most of an album. Many files carry no artist tag but name one in
     * the title ("Валерий Меладзе - Небеса"), so that is used as a fallback.
     */
    fun leadingArtists(songs: List<Song>, limit: Int = 3): String =
        leadingArtistsOf(songs.map { it.artist to it.title }, limit)

    /** [leadingArtists] over (artist tag, title) pairs. */
    fun leadingArtistsOf(artistsAndTitles: List<Pair<String, String>>, limit: Int = 3): String =
        artistsAndTitles.mapNotNull { (artist, title) -> artistOf(artist, title) }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit)
            .joinToString(", ") { it.key }

    fun artistOf(artist: String, title: String): String? {
        val tagged = artist.trim()
        // Three spellings, because the two ports read tags with different libraries: MediaStore
        // says "<unknown>", jaudiotagger leaves "Unknown Artist" or "Unknown". Each port used to
        // reject only the spelling it saw itself, so an album of untagged tracks was subtitled
        // "Unknown Artist" on one of them.
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

    /** Order-independent id for a set of tracks, by whatever the port identifies them with. */
    fun <Track : Comparable<Track>> albumId(tracks: Collection<Track>): String =
        tracks.sorted().joinToString(",").hashCode().toUInt().toString(16)

    /**
     * Identifies what the albums were built from; any change means they must be rebuilt.
     *
     * Four things can change under a cached clustering, and until the two ports met each counted
     * only some of them. The phone folded in which model variant produced the embeddings — and so
     * missed a change of [ANALYSIS_VERSION], where the vectors are replaced while the set of tracks
     * stays identical, leaving stale clusters served as current. The desktop folded in the analysis
     * version and had no notion of a variant at all. Both are in here now; a port with one model
     * passes null for the variant.
     *
     * eps is folded in as a rounded integer rather than a formatted decimal, so the signature can
     * never depend on the machine's locale.
     */
    fun <Track : Comparable<Track>> signature(
        tracks: Collection<Track>,
        variant: String?,
        epsScale: Float = 1f
    ): String {
        var hash = 1L
        for (track in tracks.sorted()) hash = hash * 1_000_003L + track.hashCode()
        return "$ANALYSIS_VERSION:$variant:${AlbumLabels.fingerprint}:" +
            "${(epsScale * 100).roundToInt()}:${tracks.size}:$hash"
    }
}
