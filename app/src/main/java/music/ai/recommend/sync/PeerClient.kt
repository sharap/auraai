package music.ai.recommend.sync

import java.net.InetSocketAddress
import java.net.Socket

/**
 * The calling side: takes what the peer has of its own, gives what the peer lacks of ours.
 *
 * One pass is one connection and both directions, because a device cannot count on being called
 * back: a phone behind a sleeping screen is not listening, so whichever side can dial does the
 * whole exchange while it is up. The order is take first, give second — if the connection dies
 * halfway, the side that is awake has at least gained something, and what it was about to give is
 * still in its own directory to give next time.
 *
 * Nothing here decides anything about records. Bytes land in a copy of the other device's
 * directory, and the readers that have been reading a shared folder for months read them exactly as
 * they read a folder Syncthing filled: same batches, same gaps, same note of how far each file was
 * read. That is the whole point of the folder having been the interface all along.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object PeerClient {

    /** What one pass moved, and why it stopped if it did. */
    data class Outcome(
        val peerDeviceId: String? = null,
        val filesTaken: Int = 0,
        val bytesTaken: Long = 0L,
        val filesGiven: Int = 0,
        val bytesGiven: Long = 0L,
        /** Null when the pass ran to the end. A word for the log and for the settings screen. */
        val trouble: String? = null
    ) {
        val moved: Boolean get() = bytesTaken > 0L || bytesGiven > 0L
    }

    /**
     * Runs one pass against the peer at [host].
     *
     * @param key the transport key from the phrase; see [PeerLink.keyFrom]. Derive it once and keep
     *   it — two hundred thousand rounds per connection would make a sync pass feel like one.
     */
    fun sync(
        host: String,
        port: Int = PeerProtocol.PORT,
        deviceId: String,
        files: PeerFiles,
        key: ByteArray,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS
    ): Outcome {
        var outcome = Outcome()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = PeerServer.TIMEOUT_MS
            socket.tcpNoDelay = true
            val link = PeerLink.connect(socket.getInputStream(), socket.getOutputStream(), key)
                ?: return outcome.copy(trouble = "would not open")

            if (!link.send(PeerProtocol.write(PeerProtocol.Request.Hello(deviceId)))) {
                return outcome.copy(trouble = "could not say hello")
            }
            val hello = link.receive()?.let(PeerProtocol::readReply) as? PeerProtocol.Reply.Hello
                ?: return outcome.copy(trouble = "no hello back")
            val peer = hello.deviceId
            if (peer == deviceId) return outcome.copy(trouble = "talking to ourselves")
            outcome = outcome.copy(peerDeviceId = peer)

            outcome = take(link, files, peer, outcome)
            if (outcome.trouble != null) return outcome
            outcome = give(link, files, outcome)
            link.send(PeerProtocol.write(PeerProtocol.Request.Bye))
            return outcome
        } catch (e: Exception) {
            return outcome.copy(trouble = e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Everything the peer has of its own that this device does not hold yet. */
    private fun take(
        link: PeerLink,
        files: PeerFiles,
        peer: String,
        start: Outcome
    ): Outcome {
        var outcome = start
        if (!link.send(PeerProtocol.write(PeerProtocol.Request.List))) {
            return outcome.copy(trouble = "could not ask what they have")
        }
        val listing = link.receive()?.let(PeerProtocol::readReply) as? PeerProtocol.Reply.Listing
            ?: return outcome.copy(trouble = "no listing")
        val held = files.heldOf(peer).associate { it.path to it.bytes }
        for (file in listing.files) {
            var at = held[file.path] ?: 0L
            // Their file being shorter than our copy means it was replaced or truncated on their
            // side. Left alone rather than patched over: the records already read are in the
            // store, and a file that disagrees with its copy is not something to guess about.
            if (at > file.bytes) continue
            var moved = false
            while (at < file.bytes) {
                val wanted = minOf(PeerProtocol.MAX_CHUNK.toLong(), file.bytes - at).toInt()
                if (!link.send(PeerProtocol.write(PeerProtocol.Request.Fetch(file.path, at, wanted)))) {
                    return outcome.copy(trouble = "could not ask for bytes")
                }
                when (val reply = link.receive()?.let(PeerProtocol::readReply)) {
                    is PeerProtocol.Reply.Chunk -> {
                        if (reply.at != at || reply.bytes.isEmpty()) break
                        val now = files.appendTo(peer, file.path, at, reply.bytes)
                            ?: return outcome.copy(trouble = "could not store ${file.path}")
                        at = now
                        moved = true
                        outcome = outcome.copy(bytesTaken = outcome.bytesTaken + reply.bytes.size)
                        if (reply.eof) break
                    }
                    is PeerProtocol.Reply.No -> break
                    else -> return outcome.copy(trouble = "not an answer about bytes")
                }
            }
            if (moved) outcome = outcome.copy(filesTaken = outcome.filesTaken + 1)
        }
        return outcome
    }

    /** Everything of this device's own that the peer's copy is short of. */
    private fun give(link: PeerLink, files: PeerFiles, start: Outcome): Outcome {
        var outcome = start
        if (!link.send(PeerProtocol.write(PeerProtocol.Request.Index))) {
            return outcome.copy(trouble = "could not ask what they hold of ours")
        }
        val index = link.receive()?.let(PeerProtocol::readReply) as? PeerProtocol.Reply.Index
            ?: return outcome.copy(trouble = "no index")
        val theirs = index.files.associate { it.path to it.bytes }
        for (file in files.own()) {
            var at = theirs[file.path] ?: 0L
            // They hold more of our file than we do. Either their copy is ahead of this device's
            // own directory — which cannot happen while only this device writes it — or they are
            // confused. Nothing useful to send either way.
            if (at > file.bytes) continue
            var moved = false
            while (at < file.bytes) {
                val chunk = files.readOwn(file.path, at, PeerProtocol.MAX_CHUNK)
                if (chunk == null || chunk.isEmpty()) break
                if (!link.send(PeerProtocol.write(PeerProtocol.Request.Put(file.path, at, chunk)))) {
                    return outcome.copy(trouble = "could not send bytes")
                }
                when (val reply = link.receive()?.let(PeerProtocol::readReply)) {
                    is PeerProtocol.Reply.Stored -> {
                        at += chunk.size
                        // Their count is what decides where the next chunk goes, so a disagreement
                        // stops this file rather than writing into a hole.
                        if (reply.bytes != at) break
                        moved = true
                        outcome = outcome.copy(bytesGiven = outcome.bytesGiven + chunk.size)
                    }
                    is PeerProtocol.Reply.No -> break
                    else -> return outcome.copy(trouble = "not an answer about storing")
                }
            }
            if (moved) outcome = outcome.copy(filesGiven = outcome.filesGiven + 1)
        }
        return outcome
    }

    /** Long enough for a phone that is awake but busy, short enough not to feel stuck. */
    const val CONNECT_TIMEOUT_MS = 4_000
}
