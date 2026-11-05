package music.ai.recommend.sync

import android.util.Log
import music.ai.recommend.history.PlayEvent
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.model.Song

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
 * library, then written to the database — and only after all of that is the state saved. A crash
 * in the middle therefore costs a re-read, which costs nothing, instead of a batch that was marked
 * read and never stored. Listens that matched nothing are kept and tried again next time, since
 * the usual reason is a file that has not been copied over yet.
 */
class SyncCoordinator(
    private val deviceId: String,
    private val state: SyncState,
    private val history: PlayHistory,
    private val library: suspend () -> List<Song>,
    private val folder: () -> SyncFolder?
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
        val fresh = history.notYetExported(offeredUpTo, PUSH_LIMIT)
        if (fresh.isNotEmpty()) {
            val keys = describeLibrary()
            for (exportable in fresh) {
                val key = keys[exportable.event.songId]
                if (key == null) {
                    // The file is gone, so there is no way left to name it to anyone. Stepping
                    // over it keeps the watermark moving; waiting would stop the outbox for good.
                    skipped++
                } else {
                    writer.offer(
                        PlayRecord(
                            playedAt = exportable.event.playedAt,
                            playedMs = exportable.event.playedMs,
                            durationMs = exportable.event.durationMs,
                            track = key
                        )
                    )
                }
                offeredUpTo = exportable.rowId
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

        val known = history.fromOtherDevices(PlayHistory.KEEP_DAYS, now)
            .map { (device, at) -> HistoryMerge.Known(device, at) }
            .toSet()
        val outcome = HistoryReader(deviceId, folder, state.progress)
            .read(known, now, PlayHistory.KEEP_DAYS)

        // Everything that ever failed to match is offered again: the usual reason is a file that
        // had not arrived yet, and by now it may have.
        val retry = state.unresolved.map { HistoryMerge.Incoming(it.deviceId, 0L, it.record) }
        val candidates = retry + outcome.merge.accepted
        if (candidates.isEmpty()) {
            state.save()
            return Pulled(0, state.unresolved.size, outcome.merge.gaps, outcome)
        }

        val matcher = TrackMatcher(describeLibrary().map { (id, key) -> TrackMatcher.Entry(id, key) })
        state.aliases.forEach { (hash, id) -> matcher.remember(hash, id) }
        val split = matcher.split(candidates)

        var takenIn = 0
        for ((device, resolved) in split.resolved.groupBy { it.incoming.deviceId }) {
            takenIn += history.recordFromOtherDevice(device, resolved.map {
                PlayEvent(
                    songId = it.id,
                    playedAt = it.incoming.record.playedAt,
                    playedMs = it.incoming.record.playedMs,
                    durationMs = it.incoming.record.durationMs
                )
            })
        }

        // Saved last, and only now: the progress map says these batches have been read, and that
        // must not be true before the rows are in the database.
        state.unresolved.clear()
        state.rememberUnresolved(split.unresolved.map { SyncState.Waiting(it.deviceId, it.record) })
        state.aliases.clear()
        state.aliases.putAll(matcher.learnedAliases())
        state.save()

        if (outcome.merge.gaps.isNotEmpty()) {
            Log.i(TAG, "Batches missing from the folder: ${outcome.merge.gaps}")
        }
        return Pulled(takenIn, state.unresolved.size, outcome.merge.gaps, outcome)
    }

    private suspend fun describeLibrary(): Map<Long, TrackKey> {
        val songs = runCatching { library() }.getOrElse {
            Log.w(TAG, "Could not read the library", it)
            emptyList()
        }
        val keys = LinkedHashMap<Long, TrackKey>(songs.size * 2)
        for (song in songs) {
            keys[song.id] = TrackKey.describe(
                artist = song.artist,
                title = song.title,
                fileName = song.path.substringAfterLast('/'),
                durationMs = song.duration
            )
        }
        return keys
    }

    private companion object {
        const val TAG = "SyncCoordinator"

        /** How many of this device's listens are described in one pass. */
        const val PUSH_LIMIT = 200
    }
}
