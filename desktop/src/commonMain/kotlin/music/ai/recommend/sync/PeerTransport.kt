package music.ai.recommend.sync

/**
 * The socket transport as the rest of the app sees it: switch it on, and call [exchange] whenever a
 * pass happens.
 *
 * It holds the three moving parts — the listener, the beacon, and the expensive key derived from
 * the owner's phrase — so that neither app has to. What it deliberately does *not* hold is any
 * opinion about records: an exchange moves bytes into a local copy of the other device's directory
 * and returns what it moved. The coordinator then reads that directory exactly as it has been
 * reading a folder somebody else filled.
 *
 * ### Why a device both listens and dials
 *
 * Because one of the two may be unable to do either. A phone is not listening while its screen is
 * off, and a desktop behind its own firewall may not be reachable; whichever side can reach the
 * other does the whole exchange, both directions, in one connection. For the common pair — a phone
 * and a desktop — the desktop listens all day and the phone dials when it is awake, which needs no
 * background service on the phone at all. That is the part of Syncthing that goes wrong on Android,
 * and the way not to have it go wrong is not to need it.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class PeerTransport(
    private val deviceId: String,
    /** The byte view of this device's own directory and its copies; null while there is none. */
    private val files: () -> PeerFiles?,
    /**
     * This device's vectors as a peer may ask for them, or null for a build that carries none.
     *
     * Fetched per call for the same reason as [files]: a listener comes up before the library has
     * been read, and one that had been handed a null once would answer "nothing here" for ever.
     */
    private val records: () -> PeerRecords? = { null },
    /** Where the local devices are, for a platform that can say; see [LocalNetwork]. */
    private val network: LocalNetwork? = null,
    /**
     * How many tracks this device has no vector for; see [Beacon]. Announced, so that a device
     * which cannot dial can still ask.
     */
    private val vectorsWanted: () -> Int = { 0 },
    /**
     * Called when a peer announces that it is short of vectors.
     *
     * The point of the call is *when* it happens. A device that cannot dial — a phone under a
     * tunnel — is reachable only on somebody else's initiative, and that initiative used to be a
     * thirty-second timer. Hearing the beacon turns the wait into a round trip, which is the
     * difference between a scan that pauses and a scan that recomputes a library.
     */
    private val onPeerInNeed: (Beacon.Peer) -> Unit = {},
    private val note: (String) -> Unit = {}
) {

    /** What the settings screen shows about the transport. */
    data class State(
        val listening: Boolean = false,
        val port: Int = -1,
        val addresses: List<String> = emptyList(),
        /** Devices heard announcing themselves, newest sighting first. */
        val peers: List<Beacon.Peer> = emptyList(),
        val last: PeerClient.Outcome? = null,
        /**
         * How many conversations this device has **answered** since the listener came up.
         *
         * Worth counting because of what zero means: the listener is up, something has been dialling
         * it or would be, and nothing has ever arrived. On a desktop that is almost always a
         * firewall — Fedora's default zone allows ssh and mDNS and nothing else — and it is the one
         * failure that looks exactly like success from the inside. The app says so rather than
         * leaving the owner to wonder why two devices on one network cannot see each other.
         */
        val served: Int = 0,
        /** Why the listener is not up, when it is not. */
        val trouble: String? = null,
        /**
         * True when every dial this pass failed *and* the socket could not be put on the local
         * network: a tunnel took the calls. Nothing in the app can undo that, but the owner can —
         * the other device dials instead, and it only needs this device's address.
         */
        val tunnelled: Boolean = false
    )

    @Volatile
    private var phrase: String? = null

    /** The derived key and the phrase it came from: two hundred thousand rounds, paid once. */
    @Volatile
    private var derived: Pair<String, ByteArray>? = null

    private var server: PeerServer? = null
    private var beacon: Beacon? = null

    private val seen = LinkedHashMap<String, Beacon.Peer>()

    /** When each peer's errand was last acted on; guarded by [seen]. See [ERRAND_FLOOR_MS]. */
    private val errands = HashMap<String, Long>()

    @Volatile
    private var lastOutcome: PeerClient.Outcome? = null

    @Volatile
    private var trouble: String? = null

    @Volatile
    private var tunnelled: Boolean = false

    /**
     * Whether some device has announced itself recently enough for its address to mean anything.
     *
     * Asked by the scan before it decides whether waiting for vectors is waiting for anybody. The
     * question is about the other device, not about this one, which is the only way to put it that
     * does not quietly assume how fast this phone happens to be.
     */
    fun somebodyNear(now: Long = System.currentTimeMillis()): Boolean =
        synchronized(seen) { seen.values.any { it.seenAt >= now - FORGET_PEERS_MS } }

    /**
     * Whether a conversation is open right now, in either direction.
     *
     * Dialled or answered, because the cost is the same from the scan's point of view: both ends
     * of one connection seal, store and read, and on a phone those compete with the analysis for
     * the same cores. Said as a fact about now rather than as a window of seconds, because a
     * window would have to guess the gap between one round of records and the next — and that gap
     * belongs to the network, which is the one thing here nobody can measure in advance.
     */
    fun conversationInFlight(): Boolean = talking.get() || (server?.inFlight() ?: 0) > 0

    /** How many conversations this device has answered; see [State.served]. */
    fun conversationsServed(): Int = served.get()

    /** How many conversations happened in either direction; see [conversations]. */
    fun conversationsHad(): Int = conversations.get()

    /** Whether a conversation is going on right now; see [exchange]. */
    private val talking = java.util.concurrent.atomic.AtomicBoolean(false)

    private val served = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Conversations that actually happened, answered or dialled, since the listener came up.
     *
     * Counted so that something waiting for vectors can wait on an event instead of on a clock.
     * "The other device has been and gone" is the one thing that reliably means *no more is
     * coming*, and it means the same on a fast phone and a slow one.
     */
    private val conversations = java.util.concurrent.atomic.AtomicInteger(0)

    val state: State
        get() = State(
            listening = server?.listening == true,
            port = server?.boundPort ?: -1,
            addresses = PeerServer.ownAddresses(),
            peers = synchronized(seen) { seen.values.sortedByDescending { it.seenAt } },
            last = lastOutcome,
            served = served.get(),
            trouble = trouble,
            tunnelled = tunnelled
        )

    /**
     * Sets the phrase the transport works under, or clears it.
     *
     * The same phrase that seals the records, and the same reasoning: it is the only secret, and a
     * device that holds it is one of the owner's. The key here is salted differently, so sealing a
     * record and sealing a conversation never use the same bytes.
     */
    fun setPhrase(text: String?) {
        val next = text?.trim()?.takeIf { it.isNotEmpty() }
        if (next == phrase) return
        phrase = next
        derived = null
    }

    private fun key(): ByteArray? {
        val held = phrase ?: return null
        derived?.let { (from, bytes) -> if (from == held) return bytes }
        return PeerLink.keyFrom(held).also { derived = held to it }
    }

    /**
     * Starts listening and announcing. Idempotent, and false when the port is taken — which on a
     * phone usually means a second copy of this app, and is worth telling the owner rather than
     * leaving them to wonder why nothing arrives.
     */
    fun start(port: Int = PeerProtocol.PORT): Boolean {
        val listener = server ?: PeerServer(
            deviceId = deviceId,
            files = object : PeerFiles {
                // Fetched per call rather than held: the directory arrives with the library, and a
                // listener started before it would otherwise serve nothing for ever.
                private fun now(): PeerFiles? = files()
                override fun own() = now()?.own() ?: emptyList()
                override fun readOwn(path: String, at: Long, max: Int) = now()?.readOwn(path, at, max)
                override fun heldOf(deviceId: String) = now()?.heldOf(deviceId) ?: emptyList()
                override fun appendTo(deviceId: String, path: String, at: Long, bytes: ByteArray) =
                    now()?.appendTo(deviceId, path, at, bytes)
            },
            key = ::key,
            records = records,
            port = port,
            onServed = { served ->
                // Counted even when the conversation went nowhere: what this answers is "has
                // anything ever reached this port", and a peer that connected and then failed to
                // open a frame has at least proved that the port is reachable.
                this.served.incrementAndGet()
                if (served.peerDeviceId != null) conversations.incrementAndGet()
                if (served.bytesGiven > 0 || served.bytesTaken > 0 || served.trouble != null) {
                    note(
                        "Peer ${served.peerDeviceId?.take(8) ?: "?"}: gave ${served.bytesGiven}, " +
                            "took ${served.bytesTaken}" + (served.trouble?.let { ", $it" } ?: "")
                    )
                }
            }
        ).also { server = it }

        val started = listener.start()
        trouble = if (started) null else "port $port is taken"
        if (!started) return false

        val announcer = beacon ?: Beacon(
            deviceId = deviceId,
            port = listener.boundPort,
            key = ::key,
            network = network,
            wanted = { runCatching { vectorsWanted() }.getOrDefault(0) },
            onSeen = { peer ->
                val errand = synchronized(seen) {
                    seen[peer.deviceId] = peer
                    // Not on every beacon that repeats the same errand. A beacon goes out every
                    // few seconds while answering one takes a conversation, so a dial per beacon
                    // would be a queue of calls that the one-at-a-time guard in [exchange] then
                    // throws away. Not once and never again either: a dial can fail, and the next
                    // beacon is the news that it is still wanted.
                    val overdue = peer.wanted > 0 &&
                        peer.seenAt - (errands[peer.deviceId] ?: 0L) >= ERRAND_FLOOR_MS
                    if (overdue) errands[peer.deviceId] = peer.seenAt
                    overdue
                }
                if (errand) runCatching { onPeerInNeed(peer) }
            }
        ).also { beacon = it }
        announcer.listen()
        return true
    }

    fun stop() {
        served.set(0)
        conversations.set(0)
        server?.stop()
        beacon?.stop()
        server = null
        beacon = null
        synchronized(seen) {
            seen.clear()
            errands.clear()
        }
        trouble = null
    }

    /**
     * One exchange with everybody worth trying: the devices heard announcing themselves, plus an
     * address the owner typed for a network that eats broadcasts.
     *
     * Announces this device first, since a pass is also the moment to be findable. Blocking, and
     * meant to be called from whatever already does the sync pass off the main thread.
     *
     * @return what each conversation moved, newest first; empty when there was nobody to talk to.
     */
    fun exchange(typedAddress: String? = null): List<PeerClient.Outcome> {
        // One conversation at a time, and the rest wait for nothing — they simply do not happen.
        // A pass calls this twice, and the timer that dials between passes calls it too; while an
        // exchange was a few round trips over byte offsets, two of them overlapping cost nothing.
        // Now one can carry a library, and two at once carried it twice: the same fourteen
        // megabytes to the same phone, from two threads, a millisecond apart.
        if (!talking.compareAndSet(false, true)) return emptyList()
        return try {
            talk(typedAddress)
        } finally {
            talking.set(false)
        }
    }

    private fun talk(typedAddress: String?): List<PeerClient.Outcome> {
        val key = key() ?: return emptyList()
        val own = files() ?: return emptyList()
        beacon?.announce()

        val fresh = System.currentTimeMillis() - FORGET_PEERS_MS
        val targets = LinkedHashMap<String, Int>()
        typedAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { typed ->
            val host = typed.substringBeforeLast(':', typed)
            val port = typed.substringAfterLast(':', "").toIntOrNull() ?: PeerProtocol.PORT
            targets[host] = port
        }
        synchronized(seen) {
            seen.values.removeAll { it.seenAt < fresh }
            for (peer in seen.values.sortedByDescending { it.seenAt }) targets[peer.address] = peer.port
        }
        if (targets.isEmpty()) return emptyList()

        val outcomes = ArrayList<PeerClient.Outcome>(targets.size)

        for ((host, port) in targets) {
            val outcome = PeerClient.sync(
                host = host,
                port = port,
                deviceId = deviceId,
                files = own,
                key = key,
                records = records(),
                network = network
            )
            outcomes.add(outcome)
            lastOutcome = outcome
            // A dial that got as far as the other device naming itself is a conversation, whether
            // or not it carried anything. One that never opened is not, and must not be: a scan
            // waiting for vectors would read it as "nobody is coming" when nobody was even asked.
            if (outcome.peerDeviceId != null) conversations.incrementAndGet()
            if (outcome.moved || outcome.trouble != null) {
                note(
                    "Peer $host: took ${outcome.bytesTaken} in ${outcome.filesTaken} files, " +
                        "gave ${outcome.bytesGiven} in ${outcome.filesGiven}" +
                        (if (outcome.vectorsTaken > 0 || outcome.vectorsGiven > 0)
                            ", vectors ${outcome.vectorsTaken} in ${outcome.vectorsGiven} out"
                        else "") +
                        (outcome.trouble?.let { ", $it" } ?: "")
                )
            }
        }
        // Said of the pass and not of one conversation: every dial failed, and at least one of
        // them failed with the socket still in the tunnel. One conversation getting through is
        // enough to stop talking about it, which is also what happens when the owner takes the
        // tunnel down.
        tunnelled = outcomes.none { it.trouble == null } && outcomes.any { it.routed == false }
        return outcomes
    }

    private companion object {
        /**
         * How long a device stays on the list after its last announcement. Three beacons' worth,
         * so one lost datagram does not make a device vanish from the owner's screen.
         */
        const val FORGET_PEERS_MS = 90_000L

        /**
         * How long one peer's errand holds before the same errand is acted on again.
         *
         * Long enough that a stream of beacons saying the same thing is one dial and not twenty,
         * short enough that a dial which failed is retried while the other device is still
         * waiting. It paces this device's own calls, so it says nothing about how fast either
         * device is — which is the point, since one of them may be a cheap phone.
         */
        const val ERRAND_FLOOR_MS = 10_000L
    }
}
