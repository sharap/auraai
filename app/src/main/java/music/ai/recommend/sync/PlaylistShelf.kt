package music.ai.recommend.sync

import music.ai.recommend.model.Song

/**
 * This app's playlists and favourites, as [SyncCoordinator] needs to see them.
 *
 * Thin on purpose: the log is shared code and so is replaying it, so what is left for the platform
 * is the two ends. What this device already has, described the way another device will hear about
 * it — which on Android means turning MediaStore ids into tags, since an id means nothing on the
 * other side. And what to show once the log has been replayed and its tracks found here, which is
 * handed straight back to the view model.
 *
 * Not a copy. The desktop port's answer to the same question is `PlaylistShelf` over paths and m3u
 * files; this one works in MediaStore ids and a preferences blob.
 */
class PlaylistShelf(
    private val songs: suspend () -> List<Song>,
    /** The favourites as this device holds them now, by MediaStore id. */
    private val favouriteIds: suspend () -> Set<Long>,
    /** The playlists as this device holds them now, in the order it shows them. */
    private val storedPlaylists: suspend () -> List<Pair<String, List<Long>>>,
    /** Where the merged answer goes: the view model replaces what it shows with this. */
    private val onShow: suspend (favourites: List<Long>, playlists: Map<String, List<Long>>) -> Unit
) : PlaylistStore<Long> {

    override suspend fun current(): PlaylistReplay.State? {
        val byId = songs().associateBy { it.id }
        fun describe(ids: Collection<Long>): List<TrackKey> =
            ids.mapNotNull { id -> byId[id]?.let { keyOf(it) } }

        val favourites = describe(favouriteIds())
        val playlists = LinkedHashMap<String, List<TrackKey>>()
        for ((name, ids) in storedPlaylists()) {
            // Kept even when empty of describable tracks: an empty playlist the owner made is
            // still a playlist, and the alternative is it quietly not travelling.
            playlists[name] = describe(ids)
        }
        if (favourites.isEmpty() && playlists.isEmpty()) return null
        return PlaylistReplay.State(favourites, playlists)
    }

    override suspend fun show(favourites: List<Long>, playlists: Map<String, List<Long>>) {
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
