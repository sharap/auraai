package music.ai.recommend.ai

import music.ai.recommend.model.Song

/**
 * The library as [DailyMix] and [Wave] want to see it: analysed tracks, each with the act to keep
 * off repeat and when it was last heard.
 *
 * A track with no vector is left out rather than given a zero one — to either of them a zero vector
 * is a direction, and it is not: it is this port's note that the file could not be decoded.
 *
 * @param identity what this port calls a track: a MediaStore id on the phone, a path on the desktop.
 * @param duplicates fill in [MixTrack.duplicateKey], which the daily mix uses to keep one recording
 *   out of a playlist twice. The wave does not: it offers one track at a time and the listener can
 *   skip, where a playlist shows all of it at once and a doubled song looks like a mistake.
 */
fun <Track> mixLibrary(
    songs: List<Song>,
    identity: (Song) -> Track,
    vectors: Map<Track, FloatArray>,
    lastPlayed: Map<Track, Long>,
    duplicates: Boolean = false
): List<MixTrack<Track>> {
    val seen = HashSet<Track>(songs.size * 2)
    return songs.mapNotNull { song ->
        val track = identity(song)
        if (!seen.add(track)) return@mapNotNull null
        val vector = vectors[track] ?: return@mapNotNull null
        val group = TrackGroups.of(song.artist, song.title, song.folderName)
        MixTrack(
            track = track,
            vector = vector,
            group = group,
            lastPlayedAt = lastPlayed[track],
            // Named the same way whether or not the file carries tags: the group is the act,
            // tagged or read off the file name, and the title has the act taken off the front.
            // The desktop built this key from the raw artist and title fields until the two code
            // bases met, so a tagged rip and an "Artist - Song.mp3" rip of one recording were two
            // different keys there and both could land in the same playlist.
            duplicateKey = if (duplicates) "$group|${TrackGroups.songInName(song.title).lowercase()}" else ""
        )
    }
}
