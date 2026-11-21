package music.ai.recommend.sync

import kotlinx.coroutines.runBlocking

/**
 * [PeerRecords] over a [SyncCoordinator]: the one place where the socket's threads meet the app's
 * coroutines.
 *
 * Every question is answered by the coordinator, which is where the library, the terms, the matcher
 * and the store already are — so the judging a record gets here is the same code, on the same
 * terms, as the judging it would get arriving in a folder. Nothing about vectors is decided in the
 * transport.
 *
 * [runBlocking] because a conversation is a blocking socket and the stores are suspending, and the
 * two have to meet somewhere. It is safe here and nowhere else: this is only ever called from a
 * conversation thread or from inside a pass that is already off the main thread, never from the
 * thread that draws. A failure answers with nothing rather than throwing — a peer that asked a
 * reasonable question gets an empty answer and tries again next time, which is what every other
 * part of this sync does when something is briefly not readable.
 *
 * The coordinator is taken as it is and never built here. A listener answers whenever a peer calls,
 * which may be long before the library has been read, and building a coordinator on that thread
 * would mean loading state and starting work from inside somebody else's phone call. Until the app
 * has one of its own, the honest answer to "what have you got" is nothing.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class VectorCourier(
    private val coordinator: () -> SyncCoordinator<*>?,
    /**
     * Where a word about not having been able to answer goes.
     *
     * Worth a channel of its own, because every failure here looks exactly like a truthful "I have
     * nothing": a peer asks what this device holds, the answer is zero, and the conversation ends
     * politely with nothing moved. That is indistinguishable from an empty library from the
     * outside, and it cost an afternoon of looking in the wrong place.
     */
    private val note: (String) -> Unit = {}
) : PeerRecords {

    /** What each peer and this device held at the last exchange; see [worthAsking]. */
    private val lastExchange = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun held(): Int = through { it.vectorsHeld() } ?: 0

    override fun lacking(): Int = through { it.vectorsLacking() } ?: 0

    override fun named(): Int = through { it.vectorsNamed() } ?: 0

    override fun wanted(max: Int): List<String> = through { it.vectorsWanted(max) } ?: emptyList()

    override fun serve(keys: List<String>): List<String> =
        through { it.serveVectors(keys) } ?: emptyList()

    override fun take(records: List<String>): Int = through { it.takeInVectors(records) } ?: 0

    override fun worthAsking(peerDeviceId: String, have: Int, lacks: Int, named: Int): Boolean =
        lastExchange[peerDeviceId] != fingerprint(have, lacks, named)

    override fun remember(peerDeviceId: String, have: Int, lacks: Int, named: Int) {
        lastExchange[peerDeviceId] = fingerprint(have, lacks, named)
    }

    /**
     * Both sides' counts in one string.
     *
     * Kept in memory and not in [SyncState] on purpose: it is an optimisation and not a fact about
     * the data, and the cost of forgetting it is one more exchange after a restart.
     */
    private fun fingerprint(have: Int, lacks: Int, named: Int): String =
        "$have:$lacks:$named:${held()}:${lacking()}:${named()}"

    private fun <T> through(body: suspend (SyncCoordinator<*>) -> T): T? {
        val present = coordinator() ?: run {
            note("A peer asked about vectors before this device had read its own library")
            return null
        }
        return try {
            runBlocking { body(present) }
        } catch (e: Exception) {
            note("Could not answer a peer about vectors: $e")
            null
        }
    }
}
