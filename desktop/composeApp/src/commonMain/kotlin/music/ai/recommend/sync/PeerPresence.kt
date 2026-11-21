package music.ai.recommend.sync

/**
 * What the scan is allowed to know about the other device, across the two halves of this process.
 *
 * The transport belongs to the view model and the scanner is built without arguments — on Android
 * the scan runs in a foreground service so that it survives the screen going off, and on the
 * desktop the scanner is an `expect class` whose constructor takes nothing and cannot be given
 * anything. Both halves share a process, but not an object graph, and the scan needs two facts
 * from the transport before it decides whether waiting for vectors is waiting for anybody.
 *
 * Deliberately only facts, and only ones that are cheap to answer: is somebody there, and have
 * they been and gone. Nothing here can start a conversation, so a stale registration cannot do
 * anything worse than make the scan wait one round and find nothing changed.
 *
 * Defaults to "nobody", which is the honest answer when the view model is gone and the beacon is
 * not listening: then a scan starts immediately, exactly as it did before any of this existed.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object PeerPresence {

    @Volatile
    private var near: () -> Boolean = { false }

    @Volatile
    private var conversations: () -> Int = { 0 }

    @Volatile
    private var inFlight: () -> Boolean = { false }

    /** Offered by whoever owns the transport, once it is up. */
    fun offer(near: () -> Boolean, conversations: () -> Int, inFlight: () -> Boolean) {
        this.near = near
        this.conversations = conversations
        this.inFlight = inFlight
    }

    /** Called when the transport goes away, so nothing is waited for on its behalf. */
    fun forget() {
        near = { false }
        conversations = { 0 }
        inFlight = { false }
    }

    /** Whether a device has announced itself recently; see `PeerTransport.somebodyNear`. */
    fun somebodyNear(): Boolean = runCatching { near() }.getOrDefault(false)

    /** Conversations in either direction; see `PeerTransport.conversationsHad`. */
    fun conversationsHad(): Int = runCatching { conversations() }.getOrDefault(0)

    /**
     * Whether a conversation is open right now; see `PeerTransport.conversationInFlight`.
     *
     * Read by the analysis between tracks, so that a phone handed a library does not spend its
     * cores computing what is arriving on them. False when there is no transport, and then the
     * analysis never steps aside for anything.
     */
    fun conversationInFlight(): Boolean = runCatching { inFlight() }.getOrDefault(false)
}
