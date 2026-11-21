package music.ai.recommend.sync

/**
 * What a device can be asked for one record at a time instead of one file at a time.
 *
 * The file half of this transport carries whole directories, which is right for history: a day of
 * listening is a few kilobytes, it is append-only, and a peer that has read the first nine tenths
 * fetches the rest by offset. Vectors are not like that at all. A library's worth is seventeen
 * megabytes, every device that holds a vector offers it again in its own directory so that the
 * folder can be a cache a wiped device is restored *from*, and the result on a socket is a great
 * deal of traffic that buys nothing: measured on this pair, a phone whose analysis had been cleared
 * three times held 4334 vectors and had written 59 197 708 bytes of them for the desktop to fetch,
 * of which the desktop took in exactly none — it had them all already.
 *
 * On a socket that duplication earns nothing, because the peer's own store *is* the second copy.
 * So vectors travel the other way round here: a device says which of its tracks have no vector,
 * and gets back only those the other one holds. Nothing is published that nobody asked for, and a
 * device with nothing missing asks for nothing and is sent nothing.
 *
 * Everything here is a string, and deliberately: a key is a written [TrackKey] and a record is a
 * written [VectorRecord], and the transport neither parses nor judges either. It carries them, and
 * the coordinator above it decides — the same judging, on the same terms, as for a batch out of a
 * folder. See [VectorCourier] for the implementation and docs/SYNC-FORMAT.md for the wire shape.
 *
 * Called from the socket's own threads: the listener answers [held], [lacking], [wanted], [serve]
 * and [take] while a conversation is open, and the dialling side calls them from the pass it is
 * part of. Implementations must therefore be safe to call from any thread, and must not touch
 * [SyncState] — a record that arrives this way leaves no progress to record, since there is no
 * file for it to be at an offset in.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface PeerRecords {

    /** How many vectors this device holds. */
    fun held(): Int

    /** How many of this device's tracks have no vector. */
    fun lacking(): Int

    /** How many of this device's tracks it can name by their sound; see `SoundPrint`. */
    fun named(): Int

    /** The keys of tracks with no vector, at most [max] of them. */
    fun wanted(max: Int): List<String>

    /**
     * The records this device holds for exactly these keys.
     *
     * Keys that name nothing here, and keys for tracks with no vector, are simply left out: an
     * answer shorter than the question is the normal case, not an error.
     */
    fun serve(keys: List<String>): List<String>

    /**
     * Takes in records a peer served, judged on the same terms as a batch from a folder.
     *
     * @return how many were stored.
     */
    fun take(records: List<String>): Int

    /**
     * Whether an exchange with this peer is worth having at all.
     *
     * Both sides' counts decide it: what each holds, what each is missing, and how much of it each
     * can name by sound. Nothing new on either count means the last answer still stands, and asking
     * again would carry a few thousand keys every half minute for ever to be told the same thing.
     * Naming belongs in it because it changes the answer without changing the totals — a device
     * that has just taken its prints can be understood where it could not be a minute ago, and a
     * pair that had settled would otherwise never find out.
     *
     * It compares counts rather than the keys themselves, so a library that swapped one track for
     * another on both counts is noticed only once something else moves — a delay, never a wrong
     * vector.
     */
    fun worthAsking(peerDeviceId: String, have: Int, lacks: Int, named: Int): Boolean

    /** Remembers what both sides held, so an unchanged pair is not asked again. */
    fun remember(peerDeviceId: String, have: Int, lacks: Int, named: Int)
}
