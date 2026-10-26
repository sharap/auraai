package music.ai.recommend.ai

/**
 * Which act a file belongs to, for the rules that keep one of them from taking over a stream.
 *
 * The artist tag would be the answer if it were there. In a library collected from downloads it
 * often is not: on the phone this was written for, 62% of 4339 tracks have no artist tag at all,
 * and what the wave used instead — the folder — is not an act. One folder held a quarter of the
 * library, so playing a single track from it made the diversity rule exclude a quarter of
 * everything for the next five picks, which pushed the stream out of what the listener actually
 * listens to and into whatever large block sat next to it.
 *
 * The file names know better. "Artist - Song (version)" is how nearly every untagged file here is
 * named, and reading the artist off the front of it turned 752 groups into 1837 and brought the
 * largest from 25.4% of the library down to 4.7%.
 *
 * Pure Kotlin, no Android dependencies.
 */
object TrackGroups {

    /** What MediaStore puts in the artist column when the file has no tag. */
    private const val UNKNOWN = "<unknown>"

    /**
     * The dash between artist and song, requiring space on at least one side.
     *
     * Without that condition "Nu-Virgos - Stop" would be filed under "Nu", and hyphenated names
     * are common enough — Jean-Michel Jarre, drum-n-bass — to matter.
     */
    private val SEPARATOR = Regex("""\s+[-–—]\s+|\s+[-–—]|[-–—]\s+""")

    /** A guest is not another act: "Artist feat. Someone" belongs with "Artist". */
    private val FEATURING = Regex("""\s+(feat\.?|ft\.?|featuring|with)\s.*$""", RegexOption.IGNORE_CASE)

    /** Longer than this and the dash was punctuation in a sentence, not a separator. */
    private const val MAX_ARTIST = 60

    /**
     * The group for [title] by [artist], filed in [folderName].
     *
     * In order of trust: the tag, the artist the file name begins with, the folder.
     */
    fun of(artist: String, title: String, folderName: String): String =
        tagged(artist) ?: artistInName(title) ?: folderName.trim().lowercase()

    private fun tagged(artist: String): String? =
        artist.trim().lowercase().takeIf { it.isNotEmpty() && it != UNKNOWN }

    /**
     * The act a file name begins with, or null when it does not look like "Artist - Song".
     *
     * Rejects a head with no letter in it, which is how a track number ("01 - Song") announces
     * itself, and a head of one character, which is more often a word than a name.
     */
    fun artistInName(title: String): String? {
        val parts = SEPARATOR.split(title.trim(), limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) return null
        val head = parts[0].trim().trim('_', '.', ' ')
        if (head.length < 2 || head.length > MAX_ARTIST) return null
        if (head.none { it.isLetter() }) return null
        return head.replace(FEATURING, "").trim().lowercase().takeIf { it.length >= 2 }
    }

    /**
     * The song, with a leading "Artist - " taken off — so that the same recording is recognised
     * whether or not whoever filed it bothered with tags.
     */
    fun songInName(title: String): String {
        val trimmed = title.trim()
        if (artistInName(trimmed) == null) return trimmed
        return SEPARATOR.split(trimmed, limit = 2)[1].trim()
    }
}
