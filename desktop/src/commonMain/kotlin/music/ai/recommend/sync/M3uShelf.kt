package music.ai.recommend.sync

import music.ai.recommend.model.Song

/**
 * This app's playlists and favourites, as [SyncCoordinator] needs to see them.
 *
 * Thin on purpose: the log is shared code and so is replaying it, so what is left for the platform
 * is the two ends. What this machine already has, described the way another device will hear about
 * it — which here means turning paths into tags, since a path on one machine says nothing about
 * where the same music sits on the other. And what to show once the log has been replayed and its
 * tracks found here.
 *
 * Not a copy. The Android app's answer to the same question is `PlaylistShelf`, over MediaStore
 * ids and a preferences blob; this one works in paths and m3u files.
 */
class M3uShelf(
    private val songs: suspend () -> List<Song>,
    /** The favourites as this machine holds them now, by path. */
    private val favouritePaths: suspend () -> Set<String>,
    /** The playlists as this machine holds them now, in the order it shows them. */
    private val storedPlaylists: suspend () -> List<Pair<String, List<String>>>,
    /** Where the merged answer goes: the view model replaces what it shows with this. */
    private val onShow: suspend (favourites: List<String>, playlists: Map<String, List<String>>) -> Unit
) : PlaylistStore<String> {

    override suspend fun current(): PlaylistReplay.State? {
        val byPath = songs().associateBy { it.path }
        fun describe(paths: Collection<String>): List<TrackKey> =
            paths.mapNotNull { path -> byPath[path]?.let { keyOf(it) } }

        val favourites = describe(favouritePaths())
        val playlists = LinkedHashMap<String, List<TrackKey>>()
        for ((name, paths) in storedPlaylists()) {
            // Kept even when empty of describable tracks: an empty playlist the owner made is
            // still a playlist, and the alternative is it quietly not travelling.
            playlists[name] = describe(paths)
        }
        if (favourites.isEmpty() && playlists.isEmpty()) return null
        return PlaylistReplay.State(favourites, playlists)
    }

    override suspend fun show(favourites: List<String>, playlists: Map<String, List<String>>) {
        onShow(favourites, playlists)
    }

    companion object {
        /** How a track of this library is described to another device; the same rule everywhere. */
        fun keyOf(song: Song): TrackKey = TrackKey.describe(
            artist = song.artist,
            title = song.title,
            fileName = song.path.substringAfterLast('/'),
            durationMs = song.duration
        )
    }
}
