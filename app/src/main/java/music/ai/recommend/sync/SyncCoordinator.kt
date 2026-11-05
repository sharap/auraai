package music.ai.recommend.sync

/**
 * The part that actually moves listening history between this device and the folder.
 *
 * Everything it stands on is tested elsewhere and on its own: the record format, the merge, the
 * batching, the day files, the matcher. What is left here is the order things happen in, and that
 * order is the whole of the correctness.
 *
 * Writing out: this device's own listens, past the watermark, described by [TrackKey.describe] and
 * handed to [HistoryWriter], which decides whether it is yet worth touching the folder. The
 * watermark moves only once a batch is on disk. A row whose file has since vanished is stepped
 * over rather than retried forever — there is nothing left to describe it by.
 *
 * Reading in: what the other devices wrote, folded in by [HistoryMerge], then matched against this
 * library, then written to the store — and only after all of that is the state saved. A crash in
 * the middle therefore costs a re-read, which costs nothing, instead of a batch that was marked
 * read and never stored. Listens that matched nothing are kept and tried again next time, since
 * the usual reason is a file that has not been copied over yet.
 *
 * Nothing here knows which app it is in. The two platforms keep history in quite different things
 * and call a track by quite different names, and both differences stop at [HistoryStore] — which
 * is the point, because this is the one file where a second copy could not be a copy, and where a
 * difference between the two would be a difference in behaviour rather than in spelling.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class SyncCoordinator<Track>(
    private val deviceId: String,
    private val state: SyncState,
    private val store: HistoryStore<Track>,
    private val folder: () -> SyncFolder?,
    /** How long this app's history is kept, which is as far back as a merge looks. */
    private val keepDays: Int = 365,
    /** Where a word about something worth noticing goes; the platform decides what that means. */
    private val note: (String) -> Unit = {}
) {

    private val writer = HistoryWriter(deviceId, Relay(), state.sequence)

    /** Rows handed to the writer but not yet on disk; lost on restart, which re-reads them. */
    private var offeredUpTo = state.exportedUpTo

    /** Where [HistoryWriter] writes, so that a failed write leaves the state untouched. */
    private inner class Relay : SyncSink {
        override fun append(relativePath: String, lines: List<String>): Boolean =
            folder()?.append(relativePath, lines) ?: false
    }

    data class Pushed(val written: Int, val pending: Int, val skipped: Int)

    data class Pulled(
        val takenIn: Int,
        val waiting: Int,
        val gaps: Map<String, List<Long>>,
        val read: HistoryReader.Outcome?
    )

    /**
     * @param force writes whatever is waiting, which is what playback stopping and the app going
     *   to the background ask for.
     */
    suspend fun push(now: Long, force: Boolean = false): Pushed {
        if (folder() == null) return Pushed(0, writer.pending, 0)
        var skipped = 0
        val fresh = store.own(offeredUpTo, PUSH_LIMIT)
        if (fresh.isNotEmpty()) {
            val keys = library()
            for (listen in fresh) {
                val key = keys[listen.track]
                if (key == null) {
                    // The file is gone, so there is no way left to name it to anyone. Stepping
                    // over it keeps the watermark moving; waiting would stop the outbox for good.
                    skipped++
                } else {
                    writer.offer(
                        PlayRecord(
                            playedAt = listen.playedAt,
                            playedMs = listen.playedMs,
                            durationMs = listen.durationMs,
                            track = key
                        )
                    )
                }
                offeredUpTo = listen.rowId
            }
        }

        val written = writer.flush(now, force)
        if (written > 0 || skipped > 0) {
            state.sequence = writer.sequence
            if (writer.pending == 0) state.exportedUpTo = offeredUpTo
            state.save()
        }
        return Pushed(written, writer.pending, skipped)
    }

    /** Folds in what the other devices have written. */
    suspend fun pull(now: Long): Pulled {
        val folder = folder() ?: return Pulled(0, state.unresolved.size, emptyMap(), null)

        val known = store.heldFromOthers(now)
        val outcome = HistoryReader(deviceId, folder, state.progress).read(known, now, keepDays)

        // Everything that ever failed to match is offered again: the usual reason is a file that
        // had not arrived yet, and by now it may have.
        val retry = state.unresolved.map { HistoryMerge.Incoming(it.deviceId, 0L, it.record) }
        val candidates = retry + outcome.merge.accepted
        if (candidates.isEmpty()) {
            state.save()
            return Pulled(0, state.unresolved.size, outcome.merge.gaps, outcome)
        }

        val matcher = TrackMatcher(library().map { (track, key) -> TrackMatcher.Entry(track, key) })
        state.aliases.forEach { (hash, text) ->
            store.fromText(text)?.let { matcher.remember(hash, it) }
        }
        val split = matcher.split(candidates)

        var takenIn = 0
        for ((device, resolved) in split.resolved.groupBy { it.incoming.deviceId }) {
            takenIn += store.takeIn(device, resolved.map {
                TakenListen(
                    track = it.id,
                    playedAt = it.incoming.record.playedAt,
                    playedMs = it.incoming.record.playedMs,
                    durationMs = it.incoming.record.durationMs
                )
            })
        }

        // Saved last, and only now: the progress map says these batches have been read, and that
        // must not be true before the rows are in the store.
        state.unresolved.clear()
        state.rememberUnresolved(split.unresolved.map { SyncState.Waiting(it.deviceId, it.record) })
        state.aliases.clear()
        state.aliases.putAll(matcher.learnedAliases().mapValues { store.asText(it.value) })
        state.save()

        if (outcome.merge.gaps.isNotEmpty()) {
            note("Batches missing from the folder: ${outcome.merge.gaps}")
        }
        return Pulled(takenIn, state.unresolved.size, outcome.merge.gaps, outcome)
    }

    /** The library, or nothing at all: a pass that cannot see it must not conclude anything. */
    private suspend fun library(): Map<Track, TrackKey> = runCatching { store.library() }.getOrElse {
        note("Could not read the library: $it")
        emptyMap()
    }

    private companion object {

        /** How many of this device's listens are described in one pass. */
        const val PUSH_LIMIT = 200
    }
}
