package music.ai.recommend.sync

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The listening side: answers what it has, hands over bytes and records, and takes in a peer's own
 * files.
 *
 * It serves exactly three things and refuses everything else. A caller may read **this** device's
 * directory, it may append to **its own** — the one named after the id it said hello with — and it
 * may ask for the vectors of tracks it names, which are served from the store rather than from any
 * file; see [PeerRecords].
 * Nothing it can say reaches another device's directory or anything outside them, because every
 * path is checked against the shapes this program's writers produce and every write goes through
 * [PeerFiles.appendTo], which knows whose directory it is being asked about.
 *
 * There is no authentication step beyond the seal, and that is not a shortcut. A frame that opens
 * was sealed under a key derived from the owner's phrase, which is the whole of the trust model
 * already: a caller that holds the phrase is one of the owner's devices. The hello is there to
 * learn *which* one, so the right directory can be offered, and not to decide whether to talk.
 *
 * On Android this runs only while the app is on the screen. That is a deliberate limit rather than
 * an unfinished one: a background socket wants a foreground service, a permanent notification and
 * a fight with the battery rules, which is exactly what makes a sync daemon unpleasant on a phone.
 * A desktop listens all the time, so phone-and-desktop — the common pair — needs nothing more; two
 * phones sync while both apps are open.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class PeerServer(
    private val deviceId: String,
    private val files: PeerFiles,
    /**
     * The transport key, or null while the owner has set no phrase.
     *
     * A function rather than a value so that the phrase can change under a running listener: the
     * next connection is then answered with the new key and the old one stops opening, which is
     * what the owner asked for when they changed it.
     */
    private val key: () -> ByteArray?,
    /**
     * Answers a peer asking for vectors by name, or null for a build that carries none and for the
     * moments before this device has read its own library.
     */
    private val records: () -> PeerRecords? = { null },
    private val port: Int = PeerProtocol.PORT,
    /** Told about each finished conversation, for the line the settings screen shows. */
    private val onServed: (Served) -> Unit = {}
) {

    /** What one conversation moved, as the device that answered it saw it. */
    data class Served(
        val peerDeviceId: String?,
        val bytesGiven: Long,
        val bytesTaken: Long,
        val trouble: String?
    )

    private val connections = AtomicInteger(0)

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var thread: Thread? = null

    val listening: Boolean get() = socket?.isClosed == false

    /** The port actually bound, which is [port] unless it was taken and zero was asked for. */
    val boundPort: Int get() = socket?.localPort ?: -1

    /**
     * Starts listening. Idempotent, and false when the port could not be bound — another copy of
     * the app, or another program altogether, in which case the owner is told rather than left
     * wondering why nothing arrives.
     */
    fun start(): Boolean {
        if (listening) return true
        return try {
            val opened = ServerSocket(port, BACKLOG)
            socket = opened
            thread = Thread({ accept(opened) }, "aura-peer-server").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: IOException) {
            socket = null
            false
        }
    }

    /**
     * How many conversations are open right now.
     *
     * Asked by the scan, which steps aside while one is: on a phone the analysis has every core
     * busy, and a device that is being handed four thousand vectors is better off taking them than
     * computing the ones it is being handed.
     */
    fun inFlight(): Int = connections.get()

    fun stop() {
        val opened = socket
        socket = null
        runCatching { opened?.close() }
        thread?.interrupt()
        thread = null
    }

    private fun accept(opened: ServerSocket) {
        while (!opened.isClosed) {
            val client = try {
                opened.accept()
            } catch (e: IOException) {
                return
            }
            // A cap rather than a pool: two devices need one conversation at a time, and the point
            // of the limit is that nothing on the network can make this phone spend threads.
            if (connections.get() >= MAX_CONNECTIONS) {
                runCatching { client.close() }
                continue
            }
            connections.incrementAndGet()
            Thread({
                try {
                    serve(client)
                } finally {
                    connections.decrementAndGet()
                }
            }, "aura-peer-conversation").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        var peer: String? = null
        var given = 0L
        var taken = 0L
        var trouble: String? = null
        try {
            client.soTimeout = TIMEOUT_MS
            client.tcpNoDelay = true
            val phraseKey = key()
            if (phraseKey == null) {
                // Nothing to listen with. Said here rather than by refusing to start, because the
                // owner may set a phrase while the app is open.
                trouble = "no phrase"
                return
            }
            val link = PeerLink.accept(client.getInputStream(), client.getOutputStream(), phraseKey)
            if (link == null) {
                trouble = "would not open"
                return
            }
            while (true) {
                val text = link.receive() ?: return
                val request = PeerProtocol.readRequest(text)
                if (request == null) {
                    // Not a message this version knows, or not one at all. The conversation ends:
                    // there is nothing to negotiate and a peer that sends nonsense gets no second
                    // chance to send more of it.
                    trouble = "unknown message"
                    return
                }
                if (request is PeerProtocol.Request.Bye) return
                if (peer == null && request !is PeerProtocol.Request.Hello) {
                    trouble = "spoke before hello"
                    return
                }
                val reply = when (request) {
                    is PeerProtocol.Request.Hello -> {
                        // One hello to a conversation: a second would mean a peer changing its
                        // name halfway, and the directory it may write to goes by that name.
                        if (peer != null) {
                            trouble = "hello twice"
                            return
                        }
                        if (request.deviceId == deviceId) {
                            trouble = "that is our own id"
                            return
                        }
                        peer = request.deviceId
                        PeerProtocol.Reply.Hello(deviceId)
                    }
                    PeerProtocol.Request.List -> PeerProtocol.Reply.Listing(files.own())
                    PeerProtocol.Request.Index ->
                        PeerProtocol.Reply.Index(files.heldOf(peer.orEmpty()))
                    is PeerProtocol.Request.Fetch -> {
                        val bytes = files.readOwn(request.path, request.at, request.max)
                        if (bytes == null) {
                            PeerProtocol.Reply.No("no such file")
                        } else {
                            PeerProtocol.Reply.Chunk(
                                at = request.at,
                                bytes = bytes,
                                // Short of what was asked for means the end: the file is only ever
                                // appended to, so there is nothing after it yet.
                                eof = bytes.size < request.max
                            ).also { given += bytes.size }
                        }
                    }
                    is PeerProtocol.Request.Put -> {
                        val held = files.appendTo(peer.orEmpty(), request.path, request.at, request.bytes)
                        if (held == null) {
                            // The usual reason is an offset that no longer lines up, which the
                            // other side sorts out by asking for the index again next pass.
                            PeerProtocol.Reply.No("offset")
                        } else {
                            taken += request.bytes.size
                            PeerProtocol.Reply.Stored(held)
                        }
                    }
                    PeerProtocol.Request.Vectors -> records()?.let { desk ->
                        PeerProtocol.Reply.Vectors(desk.held(), desk.lacking(), desk.named())
                    } ?: PeerProtocol.Reply.No("no vectors here")
                    is PeerProtocol.Request.Want -> records()?.let { desk ->
                        val served = desk.serve(request.keys)
                        // Counted as bytes given, because that is what they are: the settings
                        // screen's "gave" should not go quiet just because a vector travelled as
                        // a record instead of as part of a file.
                        given += served.sumOf { line -> line.length.toLong() }
                        PeerProtocol.Reply.Records(served)
                    } ?: PeerProtocol.Reply.No("no vectors here")
                    is PeerProtocol.Request.Wanted -> records()?.let { desk ->
                        PeerProtocol.Reply.Keys(desk.wanted(request.max))
                    } ?: PeerProtocol.Reply.No("no vectors here")
                    is PeerProtocol.Request.Give -> records()?.let { desk ->
                        taken += request.records.sumOf { line -> line.length.toLong() }
                        PeerProtocol.Reply.Took(desk.take(request.records))
                    } ?: PeerProtocol.Reply.No("no vectors here")
                    PeerProtocol.Request.Bye -> return
                }
                if (!link.send(PeerProtocol.write(reply))) return
            }
        } catch (e: Exception) {
            trouble = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { client.close() }
            onServed(Served(peer, given, taken, trouble))
        }
    }

    companion object {
        /** How long a silent peer is waited for. A phone that walked out of the room is not coming back. */
        const val TIMEOUT_MS = 20_000

        private const val BACKLOG = 4
        private const val MAX_CONNECTIONS = 4

        /** This machine's addresses, for the line that tells the owner what to type on the other device. */
        fun ownAddresses(): List<String> = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<InetAddress>()
                .filter { it.address.size == 4 && !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
