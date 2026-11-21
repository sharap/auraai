package music.ai.recommend.sync

/**
 * The playlists and the favourites as [SyncCoordinator] needs to see them.
 *
 * Small, because the log itself is shared code — [PlaylistLog] — and so is replaying it. What is
 * left for a platform is the two ends: what it already has, so that can be put into the log once,
 * and what to show once the log has been replayed and its tracks found in this library.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface PlaylistStore<Track> {

    /**
     * The playlists and favourites this device holds right now, described the way another device
     * will hear about them.
     *
     * Asked once, the first time a folder is used, and turned into the changes that would have
     * built them. Both devices do it, so two playlists of the same name become one with the tracks
     * of both — which for one person's two devices is the answer they meant, and the alternative
     * is a device whose playlists never travel at all.
     *
     * @return null when there is nothing to seed.
     */
    suspend fun current(): PlaylistReplay.State?

    /**
     * Shows the merged result: this is what the app's playlists and favourites now are.
     *
     * Tracks are the platform's own, already found in this library. A track the log mentions and
     * this device does not have is simply absent — it is still in the log, so it appears by itself
     * once the file arrives.
     */
    suspend fun show(favourites: List<Track>, playlists: Map<String, List<Track>>)
}
