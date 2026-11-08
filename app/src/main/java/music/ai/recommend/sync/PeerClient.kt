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
 * Vectors are the one thing that does not travel as a file, and [trade] is why: on a socket there
 * is somebody to ask, so a device asks for the tracks it is missing instead of both sides
 * publishing their whole library for the other to fetch. Even there nothing is decided here — the
 * keys and the records are strings this file carries and does not read; see [PeerRecords].
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
        /** Vectors asked for by name and stored here; see [trade]. */
        val vectorsTaken: Int = 0,
        /** Vectors handed over because the peer said it lacked them. */
        val vectorsGiven: Int = 0,
        /** Null when the pass ran to the end. A word for the log and for the settings screen. */
        val trouble: String? = null
    ) {
        val moved: Boolean
            get() = bytesTaken > 0L || bytesGiven > 0L || vectorsTaken > 0 || vectorsGiven > 0
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
        /** This device's vectors, or null for a build that carries none. */
        records: PeerRecords? = null,
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
            if (outcome.trouble == null && records != null) {
                outcome = trade(link, records, peer, outcome)
            }
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

    /**
     * Vectors, which travel by name instead of inside a file.
     *
     * Two halves, and both are needed because reachability is usually one-sided. The asking half
     * covers this device: it says what it is missing and takes what comes back, a library's worth
     * in one conversation if that is what a wiped device needs. The giving half covers the peer,
     * which may be unable to dial anybody and would otherwise never receive what was analysed
     * here — it asks what the peer lacks and hands over what this device holds of it.
     *
     * The giving half is bounded and best-effort: the peer answers with the first few dozen keys it
     * is missing, and if this device holds none of them the round stops rather than asking again
     * for the same list. Tracks that neither side has analysed sit at the head of that list and
     * would otherwise block it for ever. Nothing is lost by stopping — whichever device can dial
     * asks for its own gaps directly, and that half is the one that guarantees the ground is
     * covered.
     */
    private fun trade(
        link: PeerLink,
        records: PeerRecords,
        peer: String,
        start: Outcome
    ): Outcome {
        var outcome = start
        if (!link.send(PeerProtocol.write(PeerProtocol.Request.Vectors))) {
            return outcome.copy(trouble = "could not ask about vectors")
        }
        val theirs = when (val reply = link.receive()?.let(PeerProtocol::readReply)) {
            is PeerProtocol.Reply.Vectors -> reply
            // A build that carries no vectors is not a failure. There is simply nothing to trade.
            is PeerProtocol.Reply.No -> return outcome
            else -> return outcome.copy(trouble = "not an answer about vectors")
        }
        // Nothing has changed on either side since the last exchange with this peer, so the last
        // answer still stands and the keys need not be carried again to hear it.
        if (!records.worthAsking(peer, theirs.have, theirs.lacks)) return outcome

        if (theirs.have > 0) {
            for (chunk in records.wanted(MAX_WANTED).chunked(PeerProtocol.MAX_RECORDS)) {
                if (!link.send(PeerProtocol.write(PeerProtocol.Request.Want(chunk)))) {
                    return outcome.copy(trouble = "could not ask for vectors")
                }
                when (val reply = link.receive()?.let(PeerProtocol::readReply)) {
                    is PeerProtocol.Reply.Records -> {
                        if (reply.records.isEmpty()) continue
                        outcome = outcome.copy(
                            vectorsTaken = outcome.vectorsTaken + records.take(reply.records)
                        )
                    }
                    is PeerProtocol.Reply.No -> break
                    else -> return outcome.copy(trouble = "not an answer about records")
                }
            }
        }

        var rounds = 0
        while (theirs.lacks > 0 && rounds++ < MAX_ROUNDS) {
            if (!link.send(PeerProtocol.write(PeerProtocol.Request.Wanted(PeerProtocol.MAX_RECORDS)))) {
                return outcome.copy(trouble = "could not ask what they lack")
            }
            val keys = when (val reply = link.receive()?.let(PeerProtocol::readReply)) {
                is PeerProtocol.Reply.Keys -> reply.keys
                is PeerProtocol.Reply.No -> break
                else -> return outcome.copy(trouble = "not an answer about keys")
            }
            if (keys.isEmpty()) break
            val holding = records.serve(keys)
            if (holding.isEmpty()) break
            if (!link.send(PeerProtocol.write(PeerProtocol.Request.Give(holding)))) {
                return outcome.copy(trouble = "could not hand vectors over")
            }
            when (val reply = link.receive()?.let(PeerProtocol::readReply)) {
                is PeerProtocol.Reply.Took -> {
                    outcome = outcome.copy(vectorsGiven = outcome.vectorsGiven + reply.stored)
                    // They stored none of what they said they wanted. Going round again would
                    // hand over the same records to the same silence.
                    if (reply.stored == 0) break
                }
                is PeerProtocol.Reply.No -> break
                else -> return outcome.copy(trouble = "not an answer about storing")
            }
        }
        records.remember(peer, theirs.have, theirs.lacks)
        return outcome
    }

    /** Long enough for a phone that is awake but busy, short enough not to feel stuck. */
    const val CONNECT_TIMEOUT_MS = 4_000

    /**
     * The most keys this device asks about in one conversation: a whole library, because the one
     * time it matters is a device that has just lost its analysis and the answer it needs is all
     * of it.
     */
    private const val MAX_WANTED = 5_000

    /** How many times one conversation will ask what the peer lacks. */
    private const val MAX_ROUNDS = 200
}
