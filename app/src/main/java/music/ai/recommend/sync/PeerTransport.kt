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
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class PeerTransport(
    private val deviceId: String,
    /** The byte view of this device's own directory and its copies; null while there is none. */
    private val files: () -> PeerFiles?,
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
        val trouble: String? = null
    )

    @Volatile
    private var phrase: String? = null

    /** The derived key and the phrase it came from: two hundred thousand rounds, paid once. */
    @Volatile
    private var derived: Pair<String, ByteArray>? = null

    private var server: PeerServer? = null
    private var beacon: Beacon? = null

    private val seen = LinkedHashMap<String, Beacon.Peer>()

    @Volatile
    private var lastOutcome: PeerClient.Outcome? = null

    @Volatile
    private var trouble: String? = null

    private val served = java.util.concurrent.atomic.AtomicInteger(0)

    val state: State
        get() = State(
            listening = server?.listening == true,
            port = server?.boundPort ?: -1,
            addresses = PeerServer.ownAddresses(),
            peers = synchronized(seen) { seen.values.sortedByDescending { it.seenAt } },
            last = lastOutcome,
            served = served.get(),
            trouble = trouble
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
            port = port,
            onServed = { served ->
                // Counted even when the conversation went nowhere: what this answers is "has
                // anything ever reached this port", and a peer that connected and then failed to
                // open a frame has at least proved that the port is reachable.
                this.served.incrementAndGet()
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
            onSeen = { peer -> synchronized(seen) { seen[peer.deviceId] = peer } }
        ).also { beacon = it }
        announcer.listen()
        return true
    }

    fun stop() {
        served.set(0)
        server?.stop()
        beacon?.stop()
        server = null
        beacon = null
        synchronized(seen) { seen.clear() }
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
                key = key
            )
            outcomes.add(outcome)
            lastOutcome = outcome
            if (outcome.moved || outcome.trouble != null) {
                note(
                    "Peer $host: took ${outcome.bytesTaken} in ${outcome.filesTaken} files, " +
                        "gave ${outcome.bytesGiven} in ${outcome.filesGiven}" +
                        (outcome.trouble?.let { ", $it" } ?: "")
                )
            }
        }
        return outcomes
    }

    private companion object {
        /**
         * How long a device stays on the list after its last announcement. Three beacons' worth,
         * so one lost datagram does not make a device vanish from the owner's screen.
         */
        const val FORGET_PEERS_MS = 90_000L
    }
}
