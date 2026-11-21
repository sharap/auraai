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
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
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
    /** This device's playlists and favourites, with the log they are the view of. */
    private val playlists: PlaylistStore<Track>? = null,
    private val playlistLog: PlaylistLog? = null,
    /** This device's playlist of the day, or null for a build that does not carry it. */
    private val mix: MixStore<Track>? = null,
    /**
     * What seals what this device writes and opens what it reads, or nothing while the owner has
     * set no phrase.
     *
     * A lambda because the phrase can be set, corrected or cleared while the app is running, and
     * the next batch should be written under whatever is true then. Changing it makes this read
     * the folder again from the beginning; see [SyncState.sealingKey].
     */
    private val sealing: () -> Sealing? = { null },
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

    private val writer = HistoryWriter(deviceId, Relay(), state.sequence, sealing)

    private val vectorWriter = VectorWriter(deviceId, Relay(), state.vectorSequence, sealing)

    private val playlistWriter = PlaylistWriter(deviceId, Relay(), state.playlistSequence, sealing)

    private val mixWriter = MixWriter(deviceId, Relay(), state.mixSequence, sealing)

    /** Rows handed to the writer but not yet on disk; lost on restart, which re-reads them. */
    private var offeredUpTo = state.exportedUpTo

    /**
     * Sealed batches met since this object was made that would not open.
     *
     * A count for the owner and not a decision: the usual reason is a different phrase typed on
     * the other device, and that is the one way this whole arrangement fails — silently, because a
     * batch that does not open is simply counted as unreadable and stepped over. Somebody has to
     * be told.
     */
    var unopenedBatches: Int = 0
        private set

    private fun noticeUnopened(count: Int, what: String) {
        if (count <= 0) return
        unopenedBatches += count
        note(
            "Could not open $count sealed $what: the other device's phrase differs from this " +
                "one's, or this one has none"
        )
    }

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

    /**
     * Notices that the phrase has changed, and forgets where reading got to.
     *
     * A batch that could not be opened is counted as unreadable and marked read — the same as a
     * line from a newer version — so a device that had no phrase, or the wrong one, has already
     * stepped over everything the other device sealed. Clearing the progress is what makes those
     * batches be offered again, and it is safe to be generous about: history turns away what it
     * already holds, a vector for a track already analysed here is skipped, and the playlist log
     * knows its own counters. The cost of being wrong is one long pass; the cost of not doing it
     * is a folder that stays unreadable until someone deletes the state file.
     */
    private fun noticeSealingChange() {
        val fingerprint = sealing()?.fingerprint
        if (state.sealingKey == fingerprint) return
        if (state.progress.isNotEmpty()) {
            note(
                if (fingerprint == null) "The sealing phrase was cleared; reading the folder again"
                else "The sealing phrase changed; reading the folder again from the beginning"
            )
            state.progress.clear()
        }
        state.sealingKey = fingerprint
        state.save()
    }

    /** Folds in what the other devices have written. */
    suspend fun pull(now: Long): Pulled {
        val folder = folder() ?: return Pulled(0, state.unresolved.size, emptyMap(), null)
        noticeSealingChange()

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
        val outcome = HistoryReader(deviceId, folder, state.progress, sealing).read(known, now, keepDays)
        noticeUnopened(outcome.linesSealed, "batches of listens")

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
            // The ones that could not travel are marked along with the ones that did, because
            // reconsidering them every pass is a walk over the whole table for nothing.
            //
            // It also keeps a promise that matters more than the walk. A stored vector does not
            // record what decoded it — the decoder is worked out from the file's name — so the day
            // a format gains a shared decoder, every vector already held for it would start being
            // offered under the new decoder's name while having been made by the platform's. Being
            // marked already, they never are. The cost is that those files have to be analysed
            // again before they can travel, which is the owner's to ask for.
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
        noticeSealingChange()
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

        val outcome = VectorReader(deviceId, folder, state.progress, sealing).read()
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
        noticeUnopened(outcome.linesSealed, "batches of vectors")
        return PulledVectors(takenIn, refused, unmatched, alreadyHeld, outcome.more)
    }

    /**
     * ## Vectors over a socket, which are asked for rather than published
     *
     * A folder has to be written to speculatively: it is a dead drop, nobody is on the other end
     * to ask, and the copy lying in it is the only reason a device that lost its analysis can be
     * given it back. A socket is the opposite — there is somebody there, and the vector they hold
     * in their own store *is* the second copy. Publishing a library's worth into a day file for
     * them to fetch buys nothing and costs everything: measured on this pair, a phone that had its
     * analysis cleared three times had written 59 197 708 bytes of vectors for the desktop, which
     * took in none of them, having had every one already.
     *
     * So over a socket it goes the other way round. A device says which of its tracks have no
     * vector and is sent only those, and the five methods below are the whole of it. They touch no
     * file and no [SyncState]: there is nothing to record how far a file was read when there is no
     * file, and these are called from a conversation thread while a pass may be running.
     *
     * The judging is deliberately the same code as for a batch out of a folder — same terms, same
     * matcher, same verdicts — because the question "may I believe this vector" has one answer and
     * it cannot depend on which pipe the record came down.
     */
    suspend fun vectorsHeld(): Int = vectors?.analysed()?.size ?: 0

    /**
     * How many of this device's tracks it can name by their sound.
     *
     * Part of what makes an exchange worth having: a device that has taken prints since the last
     * one can now be understood where it could not be before, and two libraries whose counts have
     * not moved can still have become nameable. Without this in the reckoning, a pair that had
     * settled would never find out — measured exactly that way, a phone took 4560 prints and the
     * desktop went on holding the 157 vectors it had never been able to place.
     */
    suspend fun vectorsNamed(): Int {
        val keys = library()
        if (keys.isEmpty()) return 0
        return keys.values.count { it.hash != null }
    }

    /** How many of this device's tracks have no vector; zero before the library has been read. */
    suspend fun vectorsLacking(): Int {
        val vectors = vectors ?: return 0
        val keys = library()
        if (keys.isEmpty()) return 0
        val analysed = vectors.analysed()
        return keys.keys.count { it !in analysed }
    }

    /**
     * The keys of tracks with no vector: what this device would ask a peer for.
     *
     * Bounded by [max] because the asking side pays for it in one message, and because a first
     * exchange after a wipe is the whole library.
     */
    suspend fun vectorsWanted(max: Int): List<String> {
        val vectors = vectors ?: return emptyList()
        if (max <= 0) return emptyList()
        val keys = library()
        if (keys.isEmpty()) return emptyList()
        val analysed = vectors.analysed()
        val out = ArrayList<String>()
        for ((track, key) in keys) {
            if (track in analysed) continue
            out.add(key.written().toString())
            if (out.size >= max) break
        }
        return out
    }

    /**
     * The records this device holds for exactly these keys.
     *
     * Resolved through the same [TrackMatcher] a reader uses, so a peer naming a track slightly
     * differently is still understood, and the key written back is *this* device's own — the one
     * its library actually holds — because that is what the receiver will judge.
     */
    suspend fun serveVectors(keys: List<String>): List<String> {
        val vectors = vectors ?: return emptyList()
        if (keys.isEmpty()) return emptyList()
        // Said out loud, because from the asking side these two look exactly like "I hold nothing
        // of yours" — a conversation that ends politely having moved nothing at all.
        val terms = vectors.terms() ?: run {
            note("Asked for ${keys.size} vectors before this device can say what it analyses on")
            return emptyList()
        }
        val library = library()
        if (library.isEmpty()) {
            note("Asked for ${keys.size} vectors before this device has read its library")
            return emptyList()
        }
        val matcher = TrackMatcher(library.map { (track, key) -> TrackMatcher.Entry(track, key) })
        val analysed = vectors.analysed()
        val asked = ArrayList<Track>()
        for (text in keys) {
            val key = keyFrom(text) ?: continue
            val track = matcher.resolve(key) ?: continue
            if (track !in analysed) continue
            if (track !in asked) asked.add(track)
        }
        if (asked.isEmpty()) return emptyList()
        val out = ArrayList<String>(asked.size)
        for (one in vectors.held(asked)) {
            val key = library[one.track] ?: continue
            // The file has to be here to say which ten seconds were analysed, and a vector whose
            // excerpt cannot be named cannot be judged by anyone. Stepped over rather than sent
            // without it.
            val start = vectors.excerptStartMs(one.track) ?: continue
            out.add(
                VectorRecord(
                    track = key,
                    excerptStartMs = start,
                    decoder = vectors.decoderFor(one.track),
                    model = terms.model,
                    analysisVersion = terms.analysisVersion,
                    vector = one.vector
                ).toJson().toString()
            )
        }
        return out
    }

    /**
     * Takes in records a peer served, on the same terms as a batch out of a folder.
     *
     * Aliases learnt here are deliberately not kept: that is a note in [SyncState], this runs
     * beside whatever pass is happening, and the cost of learning the same alias again next time
     * is a string comparison.
     *
     * @return how many were stored.
     */
    suspend fun takeInVectors(records: List<String>): Int {
        val vectors = vectors ?: return 0
        if (records.isEmpty()) return 0
        val terms = vectors.terms() ?: run {
            note("${records.size} vectors offered before this device can say what it analyses on")
            return 0
        }
        val library = library()
        if (library.isEmpty()) {
            note("${records.size} vectors offered before this device has read its library")
            return 0
        }
        val matcher = TrackMatcher(library.map { (track, key) -> TrackMatcher.Entry(track, key) })
        state.aliases.forEach { (hash, text) ->
            store.fromText(text)?.let { matcher.remember(hash, it) }
        }
        val analysed = vectors.analysed()
        val taking = ArrayList<VectorStore.Held<Track>>()
        val refused = HashMap<VectorVerdict, Int>()
        var unmatched = 0
        for (text in records) {
            val record = recordFrom(text) ?: continue
            val track = matcher.resolve(record.track)
            if (track == null) {
                unmatched++
                continue
            }
            if (track in analysed) continue
            val start = vectors.excerptStartMs(track)
            if (start == null) {
                unmatched++
                continue
            }
            val verdict = terms.judge(record, vectors.decoderFor(track), start)
            if (verdict != VectorVerdict.Usable) {
                refused[verdict] = (refused[verdict] ?: 0) + 1
                continue
            }
            taking.add(VectorStore.Held(track, record.vector))
        }
        if (refused.isNotEmpty() || unmatched > 0) {
            note("Vectors by name: ${taking.size} usable, $unmatched for tracks we lack, refused $refused")
        }
        if (taking.isEmpty()) return 0
        val stored = vectors.takeIn(taking)
        // Said even when nothing was refused, because this is the only place a device that
        // answered a conversation rather than starting it can say it received anything: the
        // summary at the end belongs to whoever dialled. 4223 vectors once arrived on a phone
        // without one line admitting it, while the phone's own scanner concluded that nothing was
        // coming and set about computing them again.
        note("Vectors by name: $stored taken in")
        return stored
    }

    // Gson rather than an import, so this file goes on standing on nothing but its own package.
    private fun keyFrom(text: String): TrackKey? = jsonFrom(text)?.let { TrackKey.read(it) }

    private fun recordFrom(text: String): VectorRecord? = jsonFrom(text)?.let { VectorRecord.fromJson(it) }

    private fun jsonFrom(text: String): com.google.gson.JsonObject? = try {
        com.google.gson.JsonParser.parseString(text) as? com.google.gson.JsonObject
    } catch (e: RuntimeException) {
        null
    }

    data class PushedPlaylists(
        val written: Int,
        /** Changes made up out of what this device already had; see [PlaylistStore.current]. */
        val seeded: Int
    )

    data class PulledPlaylists(
        /** Changes added to this device's log. */
        val takenIn: Int,
        val favourites: Int,
        val playlists: Int,
        /** Tracks the log mentions that this library does not hold — not yet, usually. */
        val unmatched: Int,
        val more: Boolean
    )

    /**
     * Offers this device's playlist changes to the others.
     *
     * The first pass also puts what this device already had into the log, as the changes that
     * would have built it. Both devices do that, so two playlists of one name become one holding
     * the tracks of both — which for one person's two devices is the answer they meant, and the
     * alternative is a device whose playlists never travel at all.
     */
    suspend fun pushPlaylists(now: Long): PushedPlaylists {
        val view = playlists ?: return PushedPlaylists(0, 0)
        val log = playlistLog ?: return PushedPlaylists(0, 0)
        if (folder() == null) return PushedPlaylists(0, 0)
        // Before the library is read, what this device "already has" cannot be described: a
        // playlist is a list of tracks and a track is named by its tags. Seeding then would put an
        // empty log in place and mark it done, which is the one mistake here that cannot be undone.
        if (library().isEmpty()) return PushedPlaylists(0, 0)

        var seeded = 0
        if (!state.playlistsSeeded) {
            val current = view.current()
            if (current != null) {
                seeded = log.append(seedFrom(current, now, log.nextSeq())) ?: 0
            }
            // Marked even when there was nothing to seed, and even when storing it failed: asking
            // again next pass would describe the same playlists a second time, under new counters,
            // and every track in them would be added twice over.
            state.playlistsSeeded = true
            state.save()
        }

        val mine = log.own(deviceId, state.playlistsPublishedUpTo, PLAYLIST_PUSH_LIMIT)
        for (op in mine) playlistWriter.offer(op)
        val written = playlistWriter.flush(now, force = true)
        if (written > 0 && playlistWriter.pending == 0) {
            state.playlistSequence = playlistWriter.sequence
            state.playlistsPublishedUpTo = mine.maxOf { it.seq }
            state.save()
        }
        return PushedPlaylists(written, seeded)
    }

    /**
     * Takes in what the others changed, folds it into the log, and shows what the log now says.
     *
     * The showing happens on every pass and not only when something arrived: a change already in
     * the log may mention a track that has only just been copied over, and this is where it stops
     * being invisible.
     */
    suspend fun pullPlaylists(now: Long): PulledPlaylists {
        playlists ?: return PulledPlaylists(0, 0, 0, 0, false)
        val log = playlistLog ?: return PulledPlaylists(0, 0, 0, 0, false)
        val folder = folder() ?: return PulledPlaylists(0, 0, 0, 0, false)
        noticeSealingChange()
        val keys = library()
        if (keys.isEmpty()) return PulledPlaylists(0, 0, 0, 0, false)

        val outcome = PlaylistReader(deviceId, folder, state.progress, sealing).read()
        val added = log.append(outcome.ops)
        if (added == null) {
            // Not stored, so the folder is left looking unread and the next pass meets it again.
            note("Could not store ${outcome.ops.size} playlist changes")
            return PulledPlaylists(0, 0, 0, 0, outcome.more)
        }
        state.progress.putAll(outcome.advanced)

        val shown = showPlaylists()
        state.save()
        noticeUnopened(outcome.linesSealed, "batches of playlist changes")
        return PulledPlaylists(added, shown.favourites, shown.playlists, shown.unmatched, outcome.more)
    }

    data class PushedMix(val written: Int, val day: String?, val attempt: Int)

    data class PulledMix(
        val adopted: Boolean,
        val day: String?,
        /** Tracks of the adopted mix found in this library. */
        val tracks: Int,
        /** Tracks it named that this library does not hold. */
        val unmatched: Int
    )

    /**
     * Offers this device's playlist of the day, once per mix it built itself.
     *
     * Once, because a mix is not a change to be merged but an opinion that stands until it is
     * replaced: writing it again on every pass would be the same line over and over in the folder
     * for no one's benefit. [SyncState.mixPublished] remembers which one went out, by day and
     * attempt, so the next one the owner asks for goes out immediately.
     */
    suspend fun pushMix(now: Long): PushedMix {
        val view = mix ?: return PushedMix(0, null, 0)
        if (folder() == null) return PushedMix(0, null, 0)
        val keys = library()
        if (keys.isEmpty()) return PushedMix(0, null, 0)
        val own = view.current() ?: return PushedMix(0, null, 0)
        // A mix taken from the other device is not this device's to publish. Claiming it would
        // put two records of one list in the folder and let the two devices take turns winning
        // the day on the strength of their names.
        if (own.from != null) return PushedMix(0, own.day, own.attempt)

        val stamp = "${own.day}#${own.attempt}"
        if (state.mixPublished == stamp) return PushedMix(0, own.day, own.attempt)

        // A track whose file has gone cannot be named to anybody; the rest of the mix still can,
        // and a mix one track short is better than no mix travelling at all.
        val described = own.tracks.mapNotNull { keys[it] }
        if (described.isEmpty()) return PushedMix(0, own.day, own.attempt)

        mixWriter.offer(MixRecord(own.day, own.attempt, deviceId, described))
        val written = mixWriter.flush(now, force = true)
        if (written > 0 && mixWriter.pending == 0) {
            state.mixSequence = mixWriter.sequence
            state.mixPublished = stamp
            state.save()
        }
        return PushedMix(written, own.day, own.attempt)
    }

    /**
     * Takes the other device's mix for today when it stands above this device's.
     *
     * Only today's: a record for another day is of no use to anybody once that day is over, and a
     * record for a day this device has not reached yet — two time zones, or a few minutes either
     * side of midnight — is not today's mix here. Those are read and dropped rather than applied.
     */
    suspend fun pullMix(now: Long): PulledMix {
        val view = mix ?: return PulledMix(false, null, 0, 0)
        val folder = folder() ?: return PulledMix(false, null, 0, 0)
        noticeSealingChange()
        val keys = library()
        // Before the library is read, a mix cannot be matched against anything — and marking the
        // files read would throw away today's mix for good, since nothing keeps these records.
        if (keys.isEmpty()) return PulledMix(false, null, 0, 0)

        val today = view.today()
        val outcome = MixReader(deviceId, folder, state.progress, sealing).read()
        state.progress.putAll(outcome.advanced)
        noticeUnopened(outcome.linesSealed, "batches holding a playlist of the day")

        val offered = MixRecord.best(outcome.records, today)
        if (offered == null) {
            state.save()
            return PulledMix(false, today, 0, 0)
        }

        val ours = view.current()?.takeIf { it.day == today }
        if (ours != null) {
            val mine = ours.attempt to (ours.from ?: deviceId)
            // Equal counts as ours: that is the case where this device has already adopted this
            // very mix, and adopting it again would be work for nothing.
            if (compareValuesBy(mine, offered.order, { it.first }, { it.second }) >= 0) {
                state.save()
                return PulledMix(false, today, 0, 0)
            }
        }

        val matcher = TrackMatcher(keys.map { (track, key) -> TrackMatcher.Entry(track, key) })
        state.aliases.forEach { (hash, text) ->
            store.fromText(text)?.let { matcher.remember(hash, it) }
        }
        val tracks = offered.tracks.mapNotNull { matcher.resolve(it) }
        if (tracks.isEmpty()) {
            // Nothing of it is here. Keeping what this device has is better than showing an empty
            // day, and the record will be met again if the files arrive, because a mix this device
            // did not adopt leaves its own mix in place and the next pass compares again.
            state.save()
            return PulledMix(false, today, 0, offered.tracks.size)
        }
        view.adopt(offered.day, offered.attempt, offered.deviceId, tracks)
        state.aliases.clear()
        state.aliases.putAll(matcher.learnedAliases().mapValues { store.asText(it.value) })
        state.save()
        return PulledMix(true, today, tracks.size, offered.tracks.size - tracks.size)
    }

    data class Shown(val favourites: Int, val playlists: Int, val unmatched: Int)

    /**
     * Replays the log and shows what it says, without touching the folder.
     *
     * Called after a change made here as well as after a pass, and that is the point: what is
     * shown is always the replay of the log, so a pass cannot take away a change this device made
     * — and a change that could not be written to the log does not appear to have worked and then
     * quietly vanish when the next pass replaces the state. Being right about the log is the one
     * thing the whole design rests on, so the view is derived from it and from nothing else.
     */
    suspend fun showPlaylists(): Shown {
        val view = playlists ?: return Shown(0, 0, 0)
        val log = playlistLog ?: return Shown(0, 0, 0)
        val keys = library()
        if (keys.isEmpty()) return Shown(0, 0, 0)

        val replayed = PlaylistReplay.replay(log.all())
        val matcher = TrackMatcher(keys.map { (track, key) -> TrackMatcher.Entry(track, key) })
        state.aliases.forEach { (hash, text) ->
            // Spelled by the history store, which is the one that knows how this platform writes
            // a track down. There is one alias map and all the passes share it.
            store.fromText(text)?.let { matcher.remember(hash, it) }
        }

        var mentioned = 0
        var found = 0
        fun resolve(tracks: List<TrackKey>): List<Track> {
            mentioned += tracks.size
            return tracks.mapNotNull { matcher.resolve(it)?.also { found++ } }
        }
        val favourites = resolve(replayed.favourites)
        val lists = replayed.playlists.mapValues { resolve(it.value) }
        view.show(favourites, lists)

        state.aliases.clear()
        state.aliases.putAll(matcher.learnedAliases().mapValues { store.asText(it.value) })
        return Shown(favourites.size, lists.size, mentioned - found)
    }

    /** What this device already had, as the changes that would have built it. */
    private fun seedFrom(current: PlaylistReplay.State, now: Long, from: Long): List<PlaylistOp> {
        var seq = from
        val ops = ArrayList<PlaylistOp>()
        for (track in current.favourites) {
            ops.add(PlaylistOp(PlaylistOp.Kind.ADD, seq++, deviceId, now, null, track))
        }
        for ((name, tracks) in current.playlists) {
            // Created explicitly, so that an empty playlist is seeded as an empty playlist rather
            // than as nothing at all.
            ops.add(PlaylistOp(PlaylistOp.Kind.CREATE, seq++, deviceId, now, name))
            for (track in tracks) {
                ops.add(PlaylistOp(PlaylistOp.Kind.ADD, seq++, deviceId, now, name, track))
            }
        }
        return ops
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

        /**
         * How many playlist changes are offered in one pass.
         *
         * Generous: a change is a couple of hundred bytes, and the first pass of a device with a
         * few hundred tracks across its playlists wants all of them out at once rather than a
         * tenth of its playlists appearing on the other side.
         */
        const val PLAYLIST_PUSH_LIMIT = 20_000

        /** What a device pays for a pass if it does not say; see [publishLimit]. */
        const val DEFAULT_PUBLISH_LIMIT = 500

        /** Everything there is, for the end of an analysis; see [pushVectors]. */
        const val PUBLISH_EVERYTHING = Int.MAX_VALUE
    }
}
