package music.ai.recommend.sync

/**
 * Turns a log of changes into the favourites and the playlists.
 *
 * The log is the truth and this is the view of it. Both devices hold the same log once their files
 * have met, replay it in the same order, and therefore show the same thing — which is the whole of
 * why playlists are kept as changes rather than as a list that is overwritten. A list that is
 * overwritten has no answer to "this device added a track and that one removed another"; the newer
 * file simply wins and the other change is gone.
 *
 * The order is `(seq, deviceId)` and nothing else. The wall clock on an operation is not consulted,
 * because two devices' clocks drift and one that was off for a week will insist its own is right.
 *
 * Replay is from the beginning every time. A log of a few thousand changes is nothing to walk, and
 * the alternative — keeping a running state and applying only what is new — would need the state to
 * be right about everything that ever happened, including an operation that arrives late and
 * belongs in the middle. That is the bug this design exists to avoid.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object PlaylistReplay {

    /**
     * The favourites and the playlists, as tracks are described rather than as this device names
     * them. Turning a [TrackKey] into a local track is [TrackMatcher]'s job and happens after.
     *
     * [playlists] keeps the order tracks were added in, and keeps playlists in the order they
     * first appeared, so that two devices list them the same way.
     */
    data class State(
        val favourites: List<TrackKey>,
        val playlists: Map<String, List<TrackKey>>
    )

    fun replay(ops: Collection<PlaylistOp>): State {
        // A playlist is a list and not a set because the order tracks were added in is the order
        // they are shown in, and that order has to come out the same on both devices.
        val favourites = ArrayList<TrackKey>()
        val playlists = LinkedHashMap<String, ArrayList<TrackKey>>()

        for (op in ops.sortedWith(compareBy({ it.seq }, { it.deviceId }))) {
            when (op.kind) {
                PlaylistOp.Kind.CREATE -> playlists.getOrPut(op.playlist ?: continue) { ArrayList() }

                PlaylistOp.Kind.DELETE -> playlists.remove(op.playlist ?: continue)

                PlaylistOp.Kind.RENAME -> {
                    val from = op.playlist ?: continue
                    val to = op.newName ?: continue
                    val moved = playlists.remove(from) ?: continue
                    if (to == from) {
                        playlists[to] = moved
                    } else {
                        // Into an existing playlist of that name rather than over it: both were
                        // somebody's, and a rename is not a reason to throw one of them away.
                        val into = playlists.getOrPut(to) { ArrayList() }
                        for (track in moved) if (into.none { it == track }) into.add(track)
                    }
                }

                PlaylistOp.Kind.ADD -> {
                    val track = op.track ?: continue
                    val into = op.playlist?.let { playlists.getOrPut(it) { ArrayList() } } ?: favourites
                    // Adding what is already there is not an error and not a duplicate: two
                    // devices can add the same track before they have met.
                    if (into.none { it == track }) into.add(track)
                }

                PlaylistOp.Kind.REMOVE -> {
                    val track = op.track ?: continue
                    val from = op.playlist?.let { playlists[it] } ?: favourites
                    from.removeAll { it == track }
                }
            }
        }

        return State(favourites.toList(), playlists.mapValues { it.value.toList() })
    }

    /**
     * The counter a device's next change should carry: one more than the highest it has ever seen,
     * its own included.
     *
     * This is what makes a change that *answers* another sort after it, whatever the two clocks
     * say. A device that has seen nothing starts at one, because zero is what an operation from a
     * version that did not number them would look like, and those are refused.
     */
    fun nextSeq(ops: Collection<PlaylistOp>): Long = (ops.maxOfOrNull { it.seq } ?: 0L) + 1
}
