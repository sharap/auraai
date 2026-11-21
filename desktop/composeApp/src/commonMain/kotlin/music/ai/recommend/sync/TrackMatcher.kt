package music.ai.recommend.sync

/**
 * Turning a track as another device described it into a track in this library.
 *
 * Two rules, in this order, and the order is the point.
 *
 * **The same bytes.** If the two sides hold a file with the same hash, it is the same file and
 * there is nothing to weigh. In a collection kept in sync this answers nearly everything.
 *
 * **The same recording.** Otherwise the tags and the length decide, within a second, because a hash
 * is only decisive when it *agrees*: a flac turned into an mp3 on one device has a different hash
 * and is still the same music, so a hash that fails to match must not veto a tag match.
 *
 * The third rule the format describes — nearest neighbour in embedding space, for a file that was
 * both re-encoded and retagged — cannot apply here. A history record carries a key and not a
 * vector, and sending 512 floats along with every listen would be absurd. That rule belongs to the
 * vectors, which travel with their own embeddings; what it gives history is second-hand, through
 * [remember]: a hash resolved by any means at all is remembered, and every later record carrying
 * it resolves at once.
 *
 * Which is also how "unresolved records are kept and tried again" stays cheap. A listen whose track
 * is not here yet — not copied over, or not yet scanned — resolves to nothing and is held by the
 * caller; on the next scan the matcher is built afresh from a larger library and asked again.
 *
 * [T] is whatever the platform calls a track: a MediaStore id on the phone, a path on the desktop.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class TrackMatcher<T>(tracks: Collection<Entry<T>>) {

    /** One track of this library, as this device names it and as it would be described to another. */
    data class Entry<T>(val id: T, val key: TrackKey)

    private val byHash = HashMap<String, T>(tracks.size)
    private val byTags = HashMap<String, MutableList<Entry<T>>>(tracks.size)
    private val learned = HashMap<String, T>()

    init {
        // Insertion order decides ties, so the caller's order decides them: a library listed the
        // same way twice resolves the same way twice.
        for (entry in tracks) {
            // The long way round rather than putIfAbsent, which Android only has from API 24
            // against this app's 23. A map method that is missing on an old phone is how the
            // library scan came to die on Android 6 once before.
            entry.key.hash?.let { hash -> if (!byHash.containsKey(hash)) byHash[hash] = entry.id }
            if (entry.key.title.isNotEmpty()) {
                byTags.getOrPut(entry.key.bucket) { ArrayList(1) }.add(entry)
            }
        }
    }

    /** @return the local track, or null when this library does not hold it — yet. */
    fun resolve(key: TrackKey): T? {
        key.hash?.let { hash ->
            byHash[hash]?.let { return it }
            learned[hash]?.let { return it }
        }
        if (key.title.isEmpty()) return null

        val candidates = byTags[key.bucket] ?: return null
        var best: Entry<T>? = null
        var bestDistance = Long.MAX_VALUE
        for (candidate in candidates) {
            val distance = kotlin.math.abs(candidate.key.durationMs - key.durationMs)
            // Strictly closer, so that the first of several equally good candidates wins.
            if (distance <= TrackKey.DURATION_SLACK_MS && distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        val found = best?.id ?: return null
        key.hash?.let { learned[it] = found }
        return found
    }

    /**
     * Ties a hash to a local track for good, for the cases this matcher cannot work out on its
     * own — a file that was re-encoded and retagged, recognised by its embedding when the vectors
     * came over, or a track the owner pointed at by hand.
     */
    fun remember(hash: String, id: T) {
        learned[hash] = id
    }

    /** What has been learned beyond the library itself, for a caller that wants to keep it. */
    fun learnedAliases(): Map<String, T> = learned.toMap()

    /** One incoming listen, with the local track it turned out to be. */
    data class Resolved<T>(val id: T, val incoming: HistoryMerge.Incoming)

    /** Incoming listens split into the ones this library can place and the ones it cannot. */
    data class Split<T>(val resolved: List<Resolved<T>>, val unresolved: List<HistoryMerge.Incoming>)

    /**
     * Places what can be placed.
     *
     * The unresolved are not a failure and not a loss: they are listens of tracks this device has
     * not got, or has not analysed, and they are meant to be kept and offered again after the next
     * scan. Dropping them would quietly lose the history of everything that arrives late.
     */
    fun split(records: List<HistoryMerge.Incoming>): Split<T> {
        val resolved = ArrayList<Resolved<T>>(records.size)
        val unresolved = ArrayList<HistoryMerge.Incoming>()
        for (record in records) {
            val id = resolve(record.record.track)
            if (id == null) unresolved.add(record) else resolved.add(Resolved(id, record))
        }
        return Split(resolved, unresolved)
    }
}
