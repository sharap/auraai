package music.ai.recommend.sync

import java.text.Normalizer

/**
 * What a track is called when it has to be named to another device.
 *
 * Nothing about where the file sits can serve: the two libraries drift apart in structure and in
 * content, so a relative path is not an identity, an absolute one obviously is not, and a
 * MediaStore id belongs to one phone. What travels instead is what the recording is, by two
 * independent descriptions — the bytes of the file, and what the file says it holds.
 *
 * The two are needed because each fails where the other works. A hash is exact and free of
 * judgement, and matches everything that was copied rather than re-encoded, which is nearly all of
 * a collection kept in sync. But editing a single tag changes it. The duration with the artist and
 * title survives that, and survives a flac turned into an mp3, at the price of being a judgement
 * call about spelling.
 *
 * A third rule — nearest neighbour in embedding space, for a file that was re-encoded *and*
 * retagged — needs the vectors and so lives with the matcher rather than here.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
data class TrackKey(
    /** sha256 of the file, lowercase hex, or null when it has not been computed. */
    val hash: String?,
    /** Length in milliseconds, as the local platform reported it. */
    val durationMs: Long,
    /** Artist, normalised by [normalise]; empty when nothing said. */
    val artist: String,
    /** Title, normalised by [normalise]; empty when nothing said. */
    val title: String
) {

    /** True when these two descriptions are of the same recording. */
    fun matches(other: TrackKey): Boolean {
        if (hash != null && other.hash != null) return hash == other.hash
        if (title.isEmpty() || other.title.isEmpty()) return false
        if (title != other.title || artist != other.artist) return false
        // A second of slack: the two platforms read a duration from different places, and for a
        // variable-bitrate mp3 without a seek table one of them is estimating it.
        return kotlin.math.abs(durationMs - other.durationMs) <= DURATION_SLACK_MS
    }

    /**
     * What to look the track up by when there are many of them.
     *
     * Two keys that [matches] have to agree here or a lookup would never find them, which rules
     * out both the hash — a key with one and a key without one can still be the same track — and
     * the duration, since two readings a millisecond apart can fall either side of a second. So the
     * bucket is the tags alone and the duration is checked inside it.
     */
    val bucket: String get() = "$artist\u0000$title"

    companion object {

        /** How far two readings of the same track's length may differ. */
        const val DURATION_SLACK_MS = 1_000L

        /**
         * Describes a track in this library the way the other device will hear about it.
         *
         * Tags first, and the file's name when there are none — in this collection most files
         * carry no artist tag at all and are named `Artist - Title (version)`, so refusing to read
         * the name would leave half the library nameless and unmatchable.
         *
         * The parsing is deliberately its own and not the wave's `TrackGroups`, which does
         * something that looks the same. Grouping is a matter of taste and may be tuned whenever
         * the stream feels wrong; identity may not be tuned at all, because changing it silently
         * unmatches history that was already matched. Two jobs that happen to resemble each other.
         *
         * @param fileName the file's own name, with or without its extension; the path is not
         *   used, since where a file sits is exactly what cannot be relied on.
         * @param hash sha256 of the file when it is known, which settles everything on its own.
         */
        fun describe(
            artist: String,
            title: String,
            fileName: String,
            durationMs: Long,
            hash: String? = null
        ): TrackKey {
            val bare = fileName.substringBeforeLast('.').trim()
            val fromTags = normalise(artist).takeIf { it.isNotEmpty() && !it.equals(UNKNOWN, true) }
            val split = if (fromTags == null) splitArtistAndTitle(bare) else null
            return TrackKey(
                hash = hash,
                durationMs = durationMs,
                artist = fromTags ?: split?.first.orEmpty(),
                title = normalise(title).ifEmpty { split?.second ?: normalise(bare) }
            )
        }

        /** Android's own word for a track whose tags say nothing; it is not an artist. */
        private const val UNKNOWN = "<unknown>"

        private val SEPARATOR = Regex("""\s+[-\u2013\u2014]\s+""")

        /**
         * `Artist - Title` split on a dash with space either side, which is what distinguishes a
         * separator from the hyphen inside `Nu-Virgos` or `Jean-Michel Jarre`. Only the first dash
         * counts, so `A - B - C` is an artist and a title containing a dash.
         */
        private fun splitArtistAndTitle(name: String): Pair<String, String>? {
            val match = SEPARATOR.find(name) ?: return null
            val artist = normalise(name.substring(0, match.range.first))
            val title = normalise(name.substring(match.range.last + 1))
            if (artist.isEmpty() || title.isEmpty()) return null
            // A leading track number is not an artist.
            if (artist.all { it.isDigit() }) return null
            if (artist.none { it.isLetter() }) return null
            return artist to title
        }

        /**
         * Lower case, one spelling of each letter, one kind of space and dash, nothing else.
         *
         * Normalisation has to be the same on both devices down to the character, because it
         * decides whether a listen lands on the right track or on no track at all. It stays
         * deliberately shallow: case, accents' encoding, whitespace and the three dashes are
         * noise, while everything in brackets is not — "(Remix)" and "(Live)" are different
         * recordings and must not be folded together.
         */
        fun normalise(text: String): String {
            if (text.isEmpty()) return ""
            val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
            val builder = StringBuilder(composed.length)
            var pendingSpace = false
            for (character in composed) {
                val mapped = when (character) {
                    '–', '—', '−' -> '-'   // en dash, em dash, minus
                    ' ', ' ', ' ' -> ' '   // the non-breaking spaces
                    else -> character
                }
                if (mapped.isWhitespace()) {
                    pendingSpace = builder.isNotEmpty()
                    continue
                }
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(mapped.lowercaseChar())
            }
            return builder.toString()
        }
    }
}
