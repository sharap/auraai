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
    private val note: (String) -> Unit = {},
    /** This device's embeddings, or null for a build that does not carry vectors at all. */
    private val vectors: VectorStore<Track>? = null,
    /**
     * How many vectors one pass offers.
     *
     * A device's first exchange is its whole library — four thousand of them, eleven megabytes —
     * and what that costs depends entirely on the device, which is why this is asked of the caller.
     * On a phone each one needs its file opened to name its excerpt and the folder reached through
     * the storage access framework, so a few hundred is a few seconds and the rest can wait for the
     * next moment. On a machine with no battery and no radio, where the duration is already in hand,
     * the whole library is a second of work and spreading it over nine passes would only mean nine
     * days of a cache sitting there unshared.
     */
    private val publishLimit: Int = DEFAULT_PUBLISH_LIMIT
) {

    private val writer = HistoryWriter(deviceId, Relay(), state.sequence)

    private val vectorWriter = VectorWriter(deviceId, Relay(), state.vectorSequence)

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

        // Asked first, and the pass abandoned if there is none. Reading without a library is not
        // harmless: nothing could be placed, so every record would go to the waiting list — which
        // is capped — while the progress map would say those batches had been read. A folder
        // holding more records than the cap would lose its oldest for good. A library that is
        // merely not scanned yet is the ordinary case on a device starting up.
        val keys = library()
        if (keys.isEmpty()) return Pulled(0, state.unresolved.size, emptyMap(), null)

        // Notes about files that were never ours to read — a temporary of Syncthing's, by the look
        // of it — would otherwise sit in the state for good. Cheap to do here and nowhere else to.
        state.progress.keys.removeAll { !DayFile.isOurs(it.substringAfter('/')) }

        val known = store.heldFromOthers(now)
        val outcome = HistoryReader(deviceId, folder, state.progress).read(known, now, keepDays)

        // Everything that ever failed to match is offered again: the usual reason is a file that
        // had not arrived yet, and by now it may have.
        val retry = state.unresolved.map { HistoryMerge.Incoming(it.deviceId, 0L, it.record) }
        val candidates = retry + outcome.merge.accepted
        if (candidates.isEmpty()) {
            state.progress.putAll(outcome.advanced)
            state.save()
            return Pulled(0, state.unresolved.size, outcome.merge.gaps, outcome)
        }

        val matcher = TrackMatcher(keys.map { (track, key) -> TrackMatcher.Entry(track, key) })
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
        if (split.resolved.isNotEmpty() && takenIn == 0) {
            // Nothing was stored, so nothing may be written down as read. The folder is left as it
            // was found and the next pass meets the same batch.
            note("Could not store ${split.resolved.size} listens")
            return Pulled(0, state.unresolved.size, outcome.merge.gaps, outcome)
        }

        // Saved last, and only now: the progress map says these batches have been read, and that
        // must not be true before the rows are in the store.
        state.progress.putAll(outcome.advanced)
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

    data class PushedVectors(
        val written: Int,
        /** Held for a format with no shared decoder, so they will never travel. */
        val couldNotTravel: Int,
        /** The file is gone, or its duration cannot be read, so there is nothing to name it by. */
        val gone: Int
    )

    data class PulledVectors(
        val takenIn: Int,
        /** Offered but refused, counted by reason; see [VectorVerdict]. */
        val refused: Map<VectorVerdict, Int>,
        /** For tracks this library does not hold. Most of somebody else's library, usually. */
        val unmatched: Int,
        /** Already analysed here, so there was nothing to gain. */
        val alreadyHeld: Int,
        /** True when the pass stopped at its limit and more is waiting. */
        val more: Boolean
    )

    /**
     * Offers this device's embeddings to the others.
     *
     * A vector is a cache entry, so the order of operations is laxer than for history in one place
     * and stricter in another. Laxer: a batch written twice costs a duplicate in the folder and
     * nothing else, since the receiver stores a track's vector once. Stricter: everything the
     * vector was made from has to be named, and the excerpt has to be the one the analysis really
     * used, which is why the platform is asked rather than the key.
     *
     * The tracks are marked as dealt with only once their batch is on disk. A crash in between
     * therefore costs those vectors being offered again, which the folder absorbs.
     *
     * @param limit how many to offer in this pass, [publishLimit] by default. The end of an
     *   analysis passes [PUBLISH_EVERYTHING]: whatever was just scanned should go out as one piece
     *   rather than in dribbles that wait for somebody to come back to the app. That is the moment
     *   the work exists and the moment the owner is least surprised to find the device busy.
     */
    suspend fun pushVectors(now: Long, limit: Int = publishLimit): PushedVectors {
        val vectors = vectors ?: return PushedVectors(0, 0, 0)
        if (folder() == null) return PushedVectors(0, 0, 0)
        val terms = vectors.terms() ?: return PushedVectors(0, 0, 0)
        val keys = library()
        if (keys.isEmpty()) return PushedVectors(0, 0, 0)

        var written = 0
        var couldNotTravel = 0
        var gone = 0
        // Tracks this library does not describe. They are stepped over and *not* marked, so the
        // batch asked for grows by as many as have been met — otherwise they would sit at the head
        // of the queue and the outbox would never reach past them.
        val unnamed = HashSet<Track>()
        var rounds = 0
        while (rounds.toLong() * VectorWriter.BATCH_RECORDS < limit) {
            rounds++
            val held = vectors.toPublish(VectorWriter.BATCH_RECORDS + unnamed.size)
            if (held.isEmpty()) break
            val handled = ArrayList<Track>(held.size)
            for (one in held) {
                val key = keys[one.track]
                if (key == null) {
                    // Nothing to name it to anyone by — and crucially, not marked as dealt with.
                    // The library may simply not have been read yet, or be mid-rescan, and
                    // retiring a vector on that basis loses it for good. Learnt the hard way: a
                    // test that pushed with a three-track library retired four thousand of them.
                    unnamed.add(one.track)
                    continue
                }
                val start = vectors.excerptStartMs(one.track)
                if (start == null) {
                    // The library knows the track and the file will not open: that is gone, and
                    // the answer will not change by waiting.
                    gone++
                    handled.add(one.track)
                    continue
                }
                if (!vectorWriter.offer(
                        VectorRecord(
                            track = key,
                            excerptStartMs = start,
                            decoder = vectors.decoderFor(one.track),
                            model = terms.model,
                            analysisVersion = terms.analysisVersion,
                            vector = one.vector
                        )
                    )
                ) {
                    couldNotTravel++
                }
                handled.add(one.track)
            }
            val wrote = vectorWriter.flush(now, force = true)
            if (wrote == 0 && vectorWriter.pending > 0) break      // the folder would not take it
            written += wrote
            state.vectorSequence = vectorWriter.sequence
            // The ones that could not travel are marked along with the ones that did: a format
            // with no shared decoder will not gain one without the analysis version changing, and
            // reconsidering them every pass is a walk over the whole table for nothing.
            vectors.markHandled(handled)
            state.save()
            // No progress possible: everything on offer is unnamed, which means this library is
            // not the one these vectors belong to yet.
            if (handled.isEmpty()) break
        }
        return PushedVectors(written, couldNotTravel, gone)
    }

    /**
     * Takes in embeddings the others have offered.
     *
     * Each one is judged on the terms it carries against this device's own, and a refusal costs
     * nothing: the track is analysed here instead, which is what would have happened anyway. The
     * progress map is advanced only after the vectors are stored, so a store that fails means the
     * batch is met again rather than lost.
     */
    suspend fun pullVectors(now: Long): PulledVectors {
        val vectors = vectors ?: return PulledVectors(0, emptyMap(), 0, 0, false)
        val folder = folder() ?: return PulledVectors(0, emptyMap(), 0, 0, false)
        val terms = vectors.terms() ?: return PulledVectors(0, emptyMap(), 0, 0, false)
        val keys = library()
        if (keys.isEmpty()) return PulledVectors(0, emptyMap(), 0, 0, false)

        // What this device will believe a vector on, and whether that has changed since the folder
        // was last read. If it has, every refusal made on the old terms is undone by forgetting
        // where the reading got to: the usual cause is the owner switching to the other export of
        // the model, and on the other side of that switch a whole library is suddenly usable.
        val fingerprint = terms.fingerprint()
        if (state.vectorTerms != fingerprint) {
            if (state.vectorTerms != null) {
                note("Vector terms changed, reading the folder's vectors again")
            }
            state.progress.keys.removeAll { it.contains("/${VectorWriter.DIRECTORY}/") }
            state.vectorTerms = fingerprint
        }

        // A device holding no vectors at all has lost them — a wiped store, a file that would not
        // read — and the folder is where they are. Nothing is risked by reading it again: there is
        // nothing here to collide with, and this is the whole of what makes the folder a cache a
        // device can be restored from rather than one it can only contribute to.
        val analysed = vectors.analysed()
        if (analysed.isEmpty() && state.progress.keys.any { it.contains("/${VectorWriter.DIRECTORY}/") }) {
            note("No vectors held here; reading the folder's again")
            state.progress.keys.removeAll { it.contains("/${VectorWriter.DIRECTORY}/") }
        }

        val outcome = VectorReader(deviceId, folder, state.progress).read()
        if (outcome.records.isEmpty()) {
            state.progress.putAll(outcome.advanced)
            state.save()
            return PulledVectors(0, emptyMap(), 0, 0, outcome.more)
        }

        val matcher = TrackMatcher(keys.map { (track, key) -> TrackMatcher.Entry(track, key) })
        state.aliases.forEach { (hash, text) ->
            store.fromText(text)?.let { matcher.remember(hash, it) }
        }

        val taking = ArrayList<VectorStore.Held<Track>>()
        val refused = HashMap<VectorVerdict, Int>()
        var unmatched = 0
        var alreadyHeld = 0
        for (incoming in outcome.records) {
            val track = matcher.resolve(incoming.record.track)
            if (track == null) {
                unmatched++
                continue
            }
            if (track in analysed) {
                alreadyHeld++
                continue
            }
            val start = vectors.excerptStartMs(track)
            if (start == null) {
                // The file is here by name and not by content yet, or not at all. Nothing to
                // compare the excerpt against, so nothing to accept.
                unmatched++
                continue
            }
            val verdict = terms.judge(incoming.record, vectors.decoderFor(track), start)
            if (verdict != VectorVerdict.Usable) {
                refused[verdict] = (refused[verdict] ?: 0) + 1
                continue
            }
            taking.add(VectorStore.Held(track, incoming.record.vector))
        }

        val takenIn = if (taking.isEmpty()) 0 else vectors.takeIn(taking)
        if (taking.isNotEmpty() && takenIn == 0) {
            // Nothing was stored. The folder is left looking unread, so the next pass meets the
            // same batch instead of it being lost.
            note("Could not store ${taking.size} vectors")
            return PulledVectors(0, refused, unmatched, alreadyHeld, outcome.more)
        }
        // What came in is deliberately *not* marked as dealt with: this device will offer it in its
        // own directory too. The folder then holds every vector once per device that has it, which
        // is what makes it a cache a device can be restored from rather than one it can only
        // contribute to — if whoever analysed a track loses it, the one holding a copy hands it
        // back instead of the library being analysed again. The price is each vector carried twice
        // with two devices, which against hours of analysis is nothing.
        state.progress.putAll(outcome.advanced)
        state.aliases.clear()
        state.aliases.putAll(matcher.learnedAliases().mapValues { store.asText(it.value) })
        state.save()
        return PulledVectors(takenIn, refused, unmatched, alreadyHeld, outcome.more)
    }

    /** The library, or nothing at all: a pass that cannot see it must not conclude anything. */
    private suspend fun library(): Map<Track, TrackKey> = runCatching { store.library() }.getOrElse {
        note("Could not read the library: $it")
        emptyMap()
    }

    // Not private: the two constants below are what a caller passes in, and a platform deciding
    // its own pace has to be able to name them.
    companion object {

        /** How many of this device's listens are described in one pass. */
        const val PUSH_LIMIT = 200

        /** What a device pays for a pass if it does not say; see [publishLimit]. */
        const val DEFAULT_PUBLISH_LIMIT = 500

        /** Everything there is, for the end of an analysis; see [pushVectors]. */
        const val PUBLISH_EVERYTHING = Int.MAX_VALUE
    }
}
