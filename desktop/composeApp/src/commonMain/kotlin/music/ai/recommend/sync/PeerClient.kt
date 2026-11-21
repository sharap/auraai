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
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
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
        /**
         * Whether this device's own socket could be put on the local network.
         *
         * Null when the platform has no opinion, false when it has one and was refused — which a
         * VPN that did not ask to be bypassable makes the system do. Together with [trouble] that
         * is the difference between "the other device is not there" and "we cannot get out of the
         * tunnel to reach it", and the owner can act on exactly one of those.
         */
        val routed: Boolean? = null,
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
        /** Where the local devices are, for a platform whose routing table says otherwise. */
        network: LocalNetwork? = null,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS
    ): Outcome {
        var outcome = Outcome()
        val socket = Socket()
        try {
            // Before it is connected, which is the only moment this can be done at all. With a
            // VPN up and this line missing, the tunnel accepts the connection itself and the
            // handshake then waits for a device that is never told anybody called; see
            // [LocalNetwork].
            network?.route(socket)?.let { outcome = outcome.copy(routed = it) }
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
     * The giving half is bounded: the peer names up to a thousand of the tracks it is missing, this
     * device hands over what it holds of them a message at a time, and the round ends when the list
     * stops changing or holds nothing this device has. A thousand rather than fifty because the
     * head of that list is often a dead end — tracks neither side has analysed sit there for ever —
     * and asking fifty at a time would let those decide everything.
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
        if (!records.worthAsking(peer, theirs.have, theirs.lacks, theirs.named)) return outcome

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
        // Every key this conversation has already answered. A record the peer turned away stays in
        // the gap it names, so the next round would name it again — and the round after that. The
        // loop does not end on that, because the peer *is* making progress with the rest: it took
        // a few, so the list changed, so there must be more to do. Measured before this set was
        // here: twenty-five rounds, four thousand records, 14 676 582 bytes handed to a phone that
        // was missing two hundred and forty, and the same hundred and fifty refused every round.
        // The passes behind it waited minutes, which is how a favourite stopped travelling.
        val answered = HashSet<String>()
        while (theirs.lacks > 0 && rounds++ < MAX_ROUNDS) {
            if (!link.send(PeerProtocol.write(PeerProtocol.Request.Wanted(PeerProtocol.MAX_KEYS)))) {
                return outcome.copy(trouble = "could not ask what they lack")
            }
            val keys = when (val reply = link.receive()?.let(PeerProtocol::readReply)) {
                is PeerProtocol.Reply.Keys -> reply.keys
                is PeerProtocol.Reply.No -> break
                else -> return outcome.copy(trouble = "not an answer about keys")
            }
            if (keys.isEmpty()) break
            val fresh = keys.filterNot { it in answered }
            // Everything they name has been answered once already. Whatever they are still missing
            // after that, this device has not got or they would not take.
            if (fresh.isEmpty()) break
            answered.addAll(fresh)
            // Served and handed over a message at a time rather than all at once: naming the
            // excerpt of a thousand tracks means opening a thousand files, and the device waiting
            // for the next message has a timeout.
            var gave = 0
            for (slice in fresh.chunked(PeerProtocol.MAX_RECORDS)) {
                val holding = records.serve(slice)
                if (holding.isEmpty()) continue
                if (!link.send(PeerProtocol.write(PeerProtocol.Request.Give(holding)))) {
                    return outcome.copy(trouble = "could not hand vectors over")
                }
                when (val reply = link.receive()?.let(PeerProtocol::readReply)) {
                    is PeerProtocol.Reply.Took -> {
                        gave += holding.size
                        outcome = outcome.copy(vectorsGiven = outcome.vectorsGiven + reply.stored)
                    }
                    is PeerProtocol.Reply.No -> break
                    else -> return outcome.copy(trouble = "not an answer about storing")
                }
            }
            // Nothing of what is left is held here, so there is nothing this device can do for
            // them however many times it asks.
            if (gave == 0) break
        }
        records.remember(peer, theirs.have, theirs.lacks, theirs.named)
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
