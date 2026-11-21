package music.ai.recommend.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * How two devices find each other on a network nobody configured.
 *
 * A short broadcast every few seconds — *here is a device, at this port* — and a listener that
 * collects them. Written by hand rather than taken from mDNS because the same thirty lines then run
 * on both platforms: Android's own service discovery and a desktop mDNS library are two different
 * implementations of one agreement, which is the shape of thing that works in testing and disagrees
 * in a hotel. A broadcast is also no worse off than mDNS where the network separates its clients —
 * both are dropped — and for that case there is a field to type an address into.
 *
 * The payload is sealed under the same transport key as a conversation, which matters more here
 * than it looks: a beacon is sent to everyone on the network, repeatedly, and in the clear it would
 * announce a device's id to a café every five seconds. Sealed, it says nothing to anyone without
 * the owner's phrase — including whether the sender is a phone or a desktop, or whether two
 * beacons came from the same device.
 *
 * A nonce of random bytes rather than a counter, because a beacon has no conversation to count
 * within and a device that restarts would start again from zero. Ninety-six random bits are the
 * standard choice where messages are this rare — a device announcing itself every five seconds for
 * a century sends about six hundred million beacons, and the birthday bound for a 96-bit nonce sits
 * far above that.
 *
 * What a beacon proves is only that somebody holding the phrase was recently at that address, which
 * is all it needs to prove: it is an invitation to connect, and the connection proves the rest. A
 * replayed beacon points at an address where nothing answers, and the timestamp keeps even that
 * within [FRESH_SECONDS].
 *
 * It also carries what the sender is short of, which is how a device that cannot dial gets to ask.
 * Under a VPN that forbids bypassing the tunnel a phone can answer a conversation but never start
 * one, so everything it needs has to arrive on somebody else's initiative — and the broadcast is
 * the one thing of ours that still leaves the tunnel. A beacon saying *I am missing four thousand
 * vectors* turns the other device's next move from a thirty-second timer into an answer, and that
 * is the difference between a scan that waits a moment and a scan that computes a whole library
 * which was already on its way to it.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class Beacon(
    private val deviceId: String,
    /** The port this device's [PeerServer] is listening on. */
    private val port: Int,
    /** The transport key, or null while the owner has set no phrase — then nothing is announced. */
    private val key: () -> ByteArray?,
    /** Where the local devices are; see [LocalNetwork]. A beacon down a tunnel reaches nobody. */
    private val network: LocalNetwork? = null,
    /**
     * How many tracks this device has no vector for, as of now; zero says nothing is wanted.
     *
     * Asked afresh on every announcement rather than kept, because the whole value of the number
     * is that it is current: a device that has just been handed four thousand vectors should stop
     * asking for them in the very next breath.
     */
    private val wanted: () -> Int = { 0 },
    private val onSeen: (Peer) -> Unit = {}
) {

    /** A device that announced itself, as this device heard it. */
    data class Peer(
        val deviceId: String,
        val address: String,
        val port: Int,
        val seenAt: Long,
        /** How many vectors it said it was missing, or 0 if it said nothing of the kind. */
        val wanted: Int = 0
    )

    /** What one announcement said. */
    data class Heard(val deviceId: String, val port: Int, val wanted: Int)

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var thread: Thread? = null

    val listening: Boolean get() = socket?.isClosed == false

    /**
     * Starts listening for beacons. False when the port is taken, which on a phone usually means
     * another copy of this app — the owner is told rather than left wondering.
     */
    fun listen(): Boolean {
        if (listening) return true
        return try {
            val opened = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(java.net.InetSocketAddress(BEACON_PORT))
            }
            socket = opened
            thread = Thread({ receive(opened) }, "aura-beacon").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            socket = null
            false
        }
    }

    fun stop() {
        val opened = socket
        socket = null
        runCatching { opened?.close() }
        thread?.interrupt()
        thread = null
    }

    /**
     * Says "here I am" to the network. Called on a timer by whatever is driving sync; one datagram
     * per broadcast address, which on a phone with wifi and a hotspot is two.
     *
     * @return true when at least one datagram went out.
     */
    fun announce(now: Long = System.currentTimeMillis()): Boolean {
        val phraseKey = key() ?: return false
        val short = runCatching { wanted() }.getOrDefault(0)
        val payload = pack(deviceId, port, now, phraseKey, short) ?: return false
        var sent = false
        try {
            DatagramSocket().use { out ->
                out.broadcast = true
                network?.route(out)
                for (address in broadcastAddresses()) {
                    runCatching {
                        out.send(DatagramPacket(payload, payload.size, address, BEACON_PORT))
                        sent = true
                    }
                }
            }
        } catch (e: Exception) {
            return false
        }
        return sent
    }

    private fun receive(opened: DatagramSocket) {
        val buffer = ByteArray(MAX_BEACON)
        while (!opened.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                opened.receive(packet)
            } catch (e: Exception) {
                return
            }
            val phraseKey = key() ?: continue
            val heard = unpack(
                packet.data.copyOf(packet.length),
                phraseKey,
                System.currentTimeMillis()
            ) ?: continue
            // Our own broadcast comes back to us; so does a second copy of the app on this
            // machine, which would be a conversation with ourselves.
            if (heard.deviceId == deviceId) continue
            onSeen(
                Peer(
                    deviceId = heard.deviceId,
                    address = packet.address?.hostAddress ?: continue,
                    port = heard.port,
                    seenAt = System.currentTimeMillis(),
                    wanted = heard.wanted
                )
            )
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        runCatching {
            for (interfaces in NetworkInterface.getNetworkInterfaces()) {
                if (!interfaces.isUp || interfaces.isLoopback) continue
                for (address in interfaces.interfaceAddresses) {
                    address.broadcast?.let(out::add)
                }
            }
        }
        // The all-ones address as well: some interfaces report no broadcast address of their own,
        // and a device on the other side of the room is no use if the datagram never leaves.
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        return out.distinct()
    }

    companion object {

        /** Beside [PeerProtocol.PORT], and like it fixed rather than discovered. */
        const val BEACON_PORT = 54_334

        /** How old an announcement may be and still mean "somebody is there now". */
        const val FRESH_SECONDS = 60L

        /** Nothing this program sends is near this size; anything larger is not ours. */
        const val MAX_BEACON = 512

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val AAD = "aura-beacon-v1"

        /** The datagram: twelve bytes of nonce, then the sealed announcement. */
        fun pack(
            deviceId: String,
            port: Int,
            now: Long,
            phraseKey: ByteArray,
            /** How many vectors the sender is missing; left out of the payload when zero. */
            wanted: Int = 0,
            random: SecureRandom = SecureRandom()
        ): ByteArray? = try {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val json = JsonObject().apply {
                addProperty("d", deviceId)
                addProperty("p", port)
                addProperty("t", now / 1000)
                // Left out when there is nothing to ask for, so that the common beacon stays the
                // size it always was and an older build reads it unchanged.
                if (wanted > 0) addProperty("w", wanted)
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(phraseKey, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            nonce + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }

        /**
         * @return what the announcement said, or null for a datagram that is not ours, was sealed
         *   under another phrase, or is too old to mean anything.
         */
        fun unpack(datagram: ByteArray, phraseKey: ByteArray, now: Long): Heard? {
            if (datagram.size <= NONCE_BYTES || datagram.size > MAX_BEACON) return null
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(phraseKey, "AES"),
                    GCMParameterSpec(TAG_BITS, datagram.copyOf(NONCE_BYTES))
                )
                cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
                val plain = String(
                    cipher.doFinal(datagram, NONCE_BYTES, datagram.size - NONCE_BYTES),
                    Charsets.UTF_8
                )
                val json = JsonParser.parseString(plain) as? JsonObject ?: return null
                val deviceId = json.get("d")?.asString?.takeIf { it.isNotEmpty() } ?: return null
                val port = json.get("p")?.asInt ?: return null
                if (port !in 1..65_535) return null
                val at = json.get("t")?.asLong ?: return null
                // A beacon from the future is as suspect as one from last week: both mean the
                // datagram is not describing where a device is right now.
                if (kotlin.math.abs(now / 1000 - at) > FRESH_SECONDS) return null
                // Absent in a beacon from a build that did not say it, and in any beacon from a
                // device that wants nothing. Both mean the same thing to the reader: no errand.
                val wanted = json.get("w")?.asInt ?: 0
                Heard(deviceId, port, wanted.coerceAtLeast(0))
            } catch (e: Exception) {
                null
            }
        }
    }
}
