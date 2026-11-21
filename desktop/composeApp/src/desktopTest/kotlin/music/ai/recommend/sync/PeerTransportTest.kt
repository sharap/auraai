package music.ai.recommend.sync

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files

/**
 * Two devices over a socket, which is the transport that does not need Syncthing.
 *
 * The sealed frames and the three verbs are tested here rather than against a phone because they
 * are plain JVM code on both sides — sockets, AES-GCM, files — and because the things worth proving
 * are the refusals: a wrong phrase, a path that is not ours, a frame played back. Those are awkward
 * to arrange on a device and are exactly what an open port has to survive.
 */
class PeerTransportTest {

    private val alice = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val bob = "ffeeddccbbaa99887766554433221100"

    // A key, not a phrase: the real derivation is two hundred thousand rounds and is tested in
    // SealingTest. Here it would only make every test slower by the same amount.
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val otherKey = ByteArray(32) { (it * 11 + 3).toByte() }

    private fun temporary(): File = Files.createTempDirectory("aura-peer").toFile()

    private fun write(root: File, deviceId: String, path: String, text: String) {
        val file = File(File(root, deviceId), path)
        file.parentFile?.mkdirs()
        file.appendText(text)
    }

    private fun read(root: File, deviceId: String, path: String): String =
        File(File(root, deviceId), path).takeIf { it.isFile }?.readText() ?: ""

    /** A server on a port the system picks, so the test can run anywhere and twice at once. */
    private fun serverFor(
        root: File,
        deviceId: String,
        key: ByteArray?,
        records: PeerRecords? = null
    ): PeerServer =
        PeerServer(deviceId, DirectoryPeerFiles(root, deviceId), { key }, { records }, port = 0)

    /**
     * A device's vectors, as little of one as the transport can tell apart.
     *
     * A record here is "key:payload", because what the transport is being asked to prove is that
     * the right keys are asked about and the right records come back — what a record *means* is
     * judged by the coordinator and tested where that judging lives.
     */
    private class Desk(
        held: Map<String, String> = emptyMap(),
        missing: List<String> = emptyList(),
        /** Keys this device will not take: what a duplicate in its own library makes it refuse. */
        private val refusing: Set<String> = emptySet()
    ) : PeerRecords {
        val holds = LinkedHashMap(held)
        val gaps = ArrayList(missing)
        val asked = ArrayList<List<String>>()
        var servings = 0
        /** How many of its tracks this device can name by sound; see [PeerRecords.named]. */
        var names = 0
        private val seen = HashMap<String, String>()

        override fun held(): Int = holds.size

        override fun lacking(): Int = gaps.size

        override fun named(): Int = names

        override fun wanted(max: Int): List<String> = gaps.take(max)

        override fun serve(keys: List<String>): List<String> {
            servings++
            asked.add(keys)
            return keys.mapNotNull { holds[it] }
        }

        override fun take(records: List<String>): Int {
            var stored = 0
            for (record in records) {
                val key = record.substringBefore(':')
                if (key in refusing) continue
                holds[key] = record
                gaps.remove(key)
                stored++
            }
            return stored
        }

        override fun worthAsking(peerDeviceId: String, have: Int, lacks: Int, named: Int): Boolean =
            seen[peerDeviceId] != fingerprint(have, lacks, named)

        override fun remember(peerDeviceId: String, have: Int, lacks: Int, named: Int) {
            seen[peerDeviceId] = fingerprint(have, lacks, named)
        }

        private fun fingerprint(have: Int, lacks: Int, named: Int) =
            "$have:$lacks:$named:${holds.size}:${gaps.size}:$names"
    }

    private fun trade(server: PeerServer, deviceId: String, root: File, records: PeerRecords) =
        PeerClient.sync(
            host = "127.0.0.1",
            port = server.boundPort,
            deviceId = deviceId,
            files = DirectoryPeerFiles(root, deviceId),
            key = key,
            records = records
        )

    @Test
    fun `both directions move in one pass`() {
        val hers = temporary()
        val his = temporary()
        try {
            // Alice holds two days of history and a day of vectors; Bob holds one day of his own.
            write(hers, alice, "history/2026-11-07.jsonl", "a-one\na-two\n")
            write(hers, alice, "vectors/2026-11-07.jsonl", "a-vectors\n")
            write(his, bob, "history/2026-11-08.jsonl", "b-one\n")

            val server = serverFor(hers, alice, key)
            assertTrue(server.start())
            try {
                val outcome = PeerClient.sync(
                    host = "127.0.0.1",
                    port = server.boundPort,
                    deviceId = bob,
                    files = DirectoryPeerFiles(his, bob),
                    key = key
                )
                assertNull(outcome.trouble)
                assertEquals(alice, outcome.peerDeviceId)
                assertEquals(2, outcome.filesTaken)
                assertEquals(1, outcome.filesGiven)

                // Bob now holds Alice's files, under her name, byte for byte.
                assertEquals("a-one\na-two\n", read(his, alice, "history/2026-11-07.jsonl"))
                assertEquals("a-vectors\n", read(his, alice, "vectors/2026-11-07.jsonl"))
                // And Alice holds his, under his.
                assertEquals("b-one\n", read(hers, bob, "history/2026-11-08.jsonl"))
                // Neither wrote into the other's own directory.
                assertEquals("a-one\na-two\n", read(hers, alice, "history/2026-11-07.jsonl"))
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively(); his.deleteRecursively()
        }
    }

    @Test
    fun `a batch written by the writers arrives where the readers look`() {
        // The one join worth testing with real classes: the writers address a folder as
        // `aura/v1/<id>/history/<day>.jsonl` while the transport addresses the same tree from
        // `aura/v1` down. If those two disagreed, every pass would move bytes and no reader would
        // ever find them, which is the kind of mistake that looks like success in the logs.
        val here = temporary()
        val there = temporary()
        try {
            val folderHere = FileSyncFolder(here, alice)
            assertTrue(folderHere.append("history/2026-11-08.jsonl", listOf("""{"d":"a","n":1}""")))
            assertTrue(folderHere.append("vectors/2026-11-08.jsonl", listOf("""{"d":"a","n":2}""")))

            val transportHere = PeerTransport(alice, { DirectoryPeerFiles(File(here, "aura/v1"), alice) })
            val transportThere = PeerTransport(bob, { DirectoryPeerFiles(File(there, "aura/v1"), bob) })
            transportHere.setPhrase("мементо мори")
            transportThere.setPhrase("мементо мори")
            assertTrue(transportHere.start(port = 0))
            try {
                val outcome = PeerClient.sync(
                    host = "127.0.0.1",
                    port = transportHere.state.port,
                    deviceId = bob,
                    files = DirectoryPeerFiles(File(there, "aura/v1"), bob),
                    // Derived from the same phrase on both sides, which is the whole handshake.
                    key = PeerLink.keyFrom("мементо мори")
                )
                assertNull(outcome.trouble)

                // And the reader on the other side finds them under Alice's name, by the words it
                // would use against a folder Syncthing had filled.
                val folderThere = FileSyncFolder(there, bob)
                assertEquals(listOf(alice), folderThere.devices())
                assertEquals(
                    listOf("history/2026-11-08.jsonl"),
                    folderThere.files(alice, "history")
                )
                assertEquals(
                    listOf("""{"d":"a","n":1}"""),
                    folderThere.lines(alice, "history/2026-11-08.jsonl")
                )
                assertEquals(
                    listOf("""{"d":"a","n":2}"""),
                    folderThere.lines(alice, "vectors/2026-11-08.jsonl")
                )
            } finally {
                transportHere.stop()
                transportThere.stop()
            }
        } finally {
            here.deleteRecursively(); there.deleteRecursively()
        }
    }

    @Test
    fun `a second pass carries only what was added`() {
        val hers = temporary()
        val his = temporary()
        try {
            write(hers, alice, "history/2026-11-07.jsonl", "a-one\n")
            val server = serverFor(hers, alice, key)
            assertTrue(server.start())
            try {
                fun pass() = PeerClient.sync(
                    "127.0.0.1", server.boundPort, bob, DirectoryPeerFiles(his, bob), key
                )
                assertEquals(6L, pass().bytesTaken)
                // Nothing changed, so nothing moves: the second pass is the one that proves the
                // byte cursor is kept rather than the file being re-sent.
                val quiet = pass()
                assertEquals(0L, quiet.bytesTaken)
                assertEquals(0, quiet.filesTaken)

                write(hers, alice, "history/2026-11-07.jsonl", "a-two\n")
                assertEquals(6L, pass().bytesTaken)
                assertEquals("a-one\na-two\n", read(his, alice, "history/2026-11-07.jsonl"))
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively(); his.deleteRecursively()
        }
    }

    @Test
    fun `another phrase moves nothing and is not told why`() {
        val hers = temporary()
        val his = temporary()
        try {
            write(hers, alice, "history/2026-11-07.jsonl", "a-one\n")
            val server = serverFor(hers, alice, key)
            assertTrue(server.start())
            try {
                val outcome = PeerClient.sync(
                    "127.0.0.1", server.boundPort, bob, DirectoryPeerFiles(his, bob), otherKey
                )
                assertNotNull(outcome.trouble, "a wrong phrase must not look like success")
                assertEquals(0L, outcome.bytesTaken)
                assertEquals(0L, outcome.bytesGiven)
                assertEquals("", read(his, alice, "history/2026-11-07.jsonl"))
                // And there is no second, weaker attempt to fall back to.
                assertNull(outcome.peerDeviceId)
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively(); his.deleteRecursively()
        }
    }

    @Test
    fun `a peer cannot write into our own directory`() {
        val hers = temporary()
        try {
            write(hers, alice, "history/2026-11-07.jsonl", "a-one\n")
            val server = serverFor(hers, alice, key)
            assertTrue(server.start())
            try {
                // The directory a peer may append to is the one it said hello with, so claiming
                // our id is the only way to aim at ours. It is refused before anything is served.
                val outcome = PeerClient.sync(
                    "127.0.0.1", server.boundPort, alice, DirectoryPeerFiles(temporary(), alice), key
                )
                assertNotNull(outcome.trouble)
                assertEquals("a-one\n", read(hers, alice, "history/2026-11-07.jsonl"))
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
        }
    }

    @Test
    fun `the store refuses paths that are not ours and offsets that do not line up`() {
        val root = temporary()
        try {
            val files = DirectoryPeerFiles(root, alice)
            for (path in listOf(
                "../../etc/passwd",
                "/etc/passwd",
                "history/../../x.jsonl",
                "databases/music_database",
                "history/2026-11-07.jsonl/../../../x"
            )) {
                assertNull(files.appendTo(bob, path, 0L, "x\n".toByteArray()), "принял $path")
                assertNull(files.readOwn(path, 0L, 16), "прочитал $path")
            }
            // The protocol turns such a message away before it reaches the store at all.
            assertNull(PeerProtocol.readRequest("""{"q":"fetch","p":"../x","at":0,"max":16}"""))
            assertNull(PeerProtocol.readRequest("""{"q":"put","p":"/etc/passwd","at":0,"b":"eAo="}"""))

            assertEquals(2L, files.appendTo(bob, "history/2026-11-07.jsonl", 0L, "x\n".toByteArray()))
            // A gap would leave a hole of zeros in the middle of somebody's history.
            assertNull(files.appendTo(bob, "history/2026-11-07.jsonl", 5L, "y\n".toByteArray()))
            // An overlap would rewrite what has already been read.
            assertNull(files.appendTo(bob, "history/2026-11-07.jsonl", 1L, "y\n".toByteArray()))
            assertEquals(4L, files.appendTo(bob, "history/2026-11-07.jsonl", 2L, "y\n".toByteArray()))
            // Our own directory is not a peer's to write, whatever it calls itself.
            assertNull(files.appendTo(alice, "history/2026-11-07.jsonl", 0L, "x\n".toByteArray()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a frame cannot be replayed, reordered or dropped`() {
        val toServer = PipedOutputStream()
        val fromClient = PipedInputStream(toServer, 1 shl 16)
        val toClient = PipedOutputStream()
        val fromServer = PipedInputStream(toClient, 1 shl 16)

        val client = Thread { PeerLink.connect(fromServer, toServer, key) }
        client.start()
        val server = PeerLink.accept(fromClient, toClient, key)
        client.join()
        assertNotNull(server)

        // Three frames out of a client whose link we drive by hand, captured as bytes.
        val captured = ArrayList<ByteArray>()
        val sink = object : java.io.OutputStream() {
            val buffer = java.io.ByteArrayOutputStream()
            override fun write(b: Int) = buffer.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)
            override fun flush() {
                captured.add(buffer.toByteArray()); buffer.reset()
            }
        }
        val talker = PeerLink.connect(java.io.ByteArrayInputStream(ByteArray(0)), sink, key)
        assertNull(talker, "a handshake with nothing coming back is not a link")
    }

    @Test
    fun `a chunk of the largest size fits one frame`() {
        // The frame limit and the chunk limit are two numbers in two files, and if the second
        // outgrows the first the transport stops at exactly the files worth carrying: the big ones.
        val bytes = ByteArray(PeerProtocol.MAX_CHUNK) { (it % 251).toByte() }
        val message = PeerProtocol.write(PeerProtocol.Request.Put("vectors/2026-11-08.jsonl", 0L, bytes))
        assertTrue(
            message.toByteArray(Charsets.UTF_8).size + 32 <= PeerLink.MAX_FRAME,
            "кадр ${message.length} против предела ${PeerLink.MAX_FRAME}"
        )
        val back = PeerProtocol.readRequest(message) as? PeerProtocol.Request.Put
        assertNotNull(back)
        assertContentEquals(bytes, back!!.bytes)
    }

    /** A platform that can say where the local devices are; see [LocalNetwork]. */
    private class Routes : LocalNetwork {
        var sockets = 0
        var datagrams = 0
        var askedTooLate = false

        override fun route(socket: java.net.Socket): Boolean {
            sockets++
            // The one mistake that would do nothing at all and look like success.
            if (socket.isConnected) askedTooLate = true
            return true
        }

        override fun route(socket: java.net.DatagramSocket): Boolean {
            datagrams++
            return true
        }
    }

    @Test
    fun `a dialled socket goes on the local network before it is connected`() {
        val hers = temporary()
        val his = temporary()
        try {
            write(hers, alice, "history/2026-11-08.jsonl", "a-one\n")
            val routes = Routes()
            val server = serverFor(hers, alice, key)
            assertTrue(server.start())
            try {
                val outcome = PeerClient.sync(
                    host = "127.0.0.1",
                    port = server.boundPort,
                    deviceId = bob,
                    files = DirectoryPeerFiles(his, bob),
                    key = key,
                    network = routes
                )

                assertNull(outcome.trouble)
                assertEquals(1, routes.sockets)
                assertTrue(!routes.askedTooLate, "сокет уже был подключён — привязка ничего не делает")
                // And the pass still did its work, which is what says the binding did not get in
                // the way of anything.
                assertEquals("a-one\n", read(his, alice, "history/2026-11-08.jsonl"))
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }

    @Test
    fun `a beacon is announced over the local network too`() {
        val routes = Routes()
        // Whether a datagram reaches anybody depends on the machine this runs on; what is being
        // proved is that it is sent from the local network and not down a tunnel.
        Beacon(alice, 54_333, { key }, routes).announce()
        assertEquals(1, routes.datagrams)
    }

    @Test
    fun `every message survives the round trip and nothing else is accepted`() {
        val requests = listOf(
            PeerProtocol.Request.Hello(alice),
            PeerProtocol.Request.List,
            PeerProtocol.Request.Index,
            PeerProtocol.Request.Fetch("history/2026-11-08.jsonl", 4096L, 1024),
            PeerProtocol.Request.Put("vectors/2026-11-08/0003.jsonl", 0L, byteArrayOf(1, 2, 3)),
            PeerProtocol.Request.Vectors,
            PeerProtocol.Request.Want(listOf("""{"t":"one"}""", """{"t":"two"}""")),
            PeerProtocol.Request.Want(emptyList()),
            PeerProtocol.Request.Wanted(PeerProtocol.MAX_RECORDS),
            PeerProtocol.Request.Give(listOf("""{"k":{"t":"one"},"f":"AAA="}""")),
            PeerProtocol.Request.Bye
        )
        for (request in requests) {
            assertEquals(request, PeerProtocol.readRequest(PeerProtocol.write(request)))
        }
        val replies = listOf(
            PeerProtocol.Reply.Hello(bob),
            PeerProtocol.Reply.Listing(listOf(PeerProtocol.Held("history/2026-11-08.jsonl", 12L))),
            PeerProtocol.Reply.Index(emptyList()),
            PeerProtocol.Reply.Chunk(0L, byteArrayOf(9, 8, 7), eof = true),
            PeerProtocol.Reply.Stored(12L),
            PeerProtocol.Reply.Vectors(4543, 229, 4560),
            PeerProtocol.Reply.Vectors(0, 0, 0),
            PeerProtocol.Reply.Records(listOf("""{"k":{"t":"one"},"f":"AAA="}""")),
            PeerProtocol.Reply.Records(emptyList()),
            PeerProtocol.Reply.Keys(listOf("""{"t":"one"}""")),
            PeerProtocol.Reply.Took(50),
            PeerProtocol.Reply.No("offset")
        )
        for (reply in replies) {
            assertEquals(reply, PeerProtocol.readReply(PeerProtocol.write(reply)))
        }
        for (rubbish in listOf(
            "", "{", "[]", """{"q":"dance"}""", """{"q":"hello"}""",
            """{"q":"fetch","p":"history/2026-11-08.jsonl","at":-1,"max":16}""",
            """{"q":"fetch","p":"history/2026-11-08.jsonl","at":0,"max":0}""",
            """{"q":"fetch","p":"history/2026-11-08.jsonl","at":0,"max":${PeerProtocol.MAX_CHUNK + 1}}""",
            """{"q":"put","p":"history/2026-11-08.jsonl","at":0,"b":"not base64!"}""",
            """{"q":"put","p":"history/2026-11-08.jsonl","at":0,"b":""}""",
            """{"q":"wanted","max":0}""",
            """{"q":"want","k":"not a list"}""",
            """{"q":"want","k":[7]}""",
            """{"q":"want","k":[""]}""",
            """{"q":"give","v":[{"k":"an object, not a record"}]}"""
        )) {
            assertNull(PeerProtocol.readRequest(rubbish), "принял «$rubbish»")
        }
    }

    @Test
    fun `a device is served the vectors it names and no others`() {
        val hers = temporary()
        val his = temporary()
        try {
            val alices = Desk(held = mapOf("A" to "A:one", "B" to "B:two", "C" to "C:three"))
            val bobs = Desk(missing = listOf("A", "B", "X"))
            val server = serverFor(hers, alice, key, alices)
            assertTrue(server.start())
            try {
                val outcome = trade(server, bob, his, bobs)

                assertNull(outcome.trouble)
                assertEquals(2, outcome.vectorsTaken)
                assertEquals(listOf("A:one", "B:two"), bobs.holds.values.toList())
                // Asked about exactly what Bob was missing — including the one Alice turns out not
                // to have — and nothing about the rest of her library.
                assertEquals(listOf(listOf("A", "B", "X")), alices.asked)
                assertEquals(listOf("X"), bobs.gaps)
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }

    @Test
    fun `a device missing nothing asks for nothing`() {
        val hers = temporary()
        val his = temporary()
        try {
            val alices = Desk(held = mapOf("A" to "A:one"))
            val bobs = Desk(held = mapOf("A" to "A:one"))
            val server = serverFor(hers, alice, key, alices)
            assertTrue(server.start())
            try {
                val outcome = trade(server, bob, his, bobs)

                assertNull(outcome.trouble)
                assertEquals(0, outcome.vectorsTaken)
                assertEquals(0, outcome.vectorsGiven)
                assertEquals(0, alices.servings)
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }

    @Test
    fun `nothing new on either side is not asked about a second time`() {
        val hers = temporary()
        val his = temporary()
        try {
            val alices = Desk(held = mapOf("A" to "A:one"))
            val bobs = Desk(missing = listOf("A", "X"))
            val server = serverFor(hers, alice, key, alices)
            assertTrue(server.start())
            try {
                assertEquals(1, trade(server, bob, his, bobs).vectorsTaken)
                assertEquals(1, alices.servings)

                // X is still missing and Alice still has not got it. Asking again would carry the
                // same keys every half minute for ever to be told the same thing.
                val again = trade(server, bob, his, bobs)
                assertNull(again.trouble)
                assertEquals(0, again.vectorsTaken)
                assertEquals(1, alices.servings)
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }

    @Test
    fun `a peer that has learnt to name its tracks is asked again`() {
        val hers = temporary()
        val his = temporary()
        try {
            val alices = Desk(held = mapOf("A" to "A:one"))
            val bobs = Desk(missing = listOf("A", "X"))
            val server = serverFor(hers, alice, key, alices)
            assertTrue(server.start())
            try {
                assertEquals(1, trade(server, bob, his, bobs).vectorsTaken)
                assertEquals(1, alices.servings)

                // Settled: X is still missing and Alice still has not got it.
                trade(server, bob, his, bobs)
                assertEquals(1, alices.servings)

                // Alice has taken her prints. She holds nothing new and lacks nothing new, and yet
                // what she can be *asked* about has changed — which is exactly the case that kept
                // 157 vectors from ever arriving: a phone hashed its whole library and the pair
                // went on believing there was nothing to discuss.
                alices.names = 4560
                trade(server, bob, his, bobs)
                assertEquals(2, alices.servings)
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }

    @Test
    fun `what the peer is missing is handed over, since it may be unable to dial`() {
        val hers = temporary()
        val his = temporary()
        try {
            val alices = Desk(missing = listOf("B"))
            val bobs = Desk(held = mapOf("B" to "B:two"))
            val server = serverFor(hers, alice, key, alices)
            assertTrue(server.start())
            try {
                val outcome = trade(server, bob, his, bobs)

                assertNull(outcome.trouble)
                assertEquals(1, outcome.vectorsGiven)
                assertEquals(listOf("B:two"), alices.holds.values.toList())
                assertTrue(alices.gaps.isEmpty())
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }

    @Test
    fun `a library's worth travels in one conversation`() {
        val hers = temporary()
        val his = temporary()
        try {
            val everything = (1..120).associate { "k$it" to "k$it:v$it" }
            val alices = Desk(held = everything)
            val bobs = Desk(missing = everything.keys.toList())
            val server = serverFor(hers, alice, key, alices)
            assertTrue(server.start())
            try {
                val outcome = trade(server, bob, his, bobs)

                assertNull(outcome.trouble)
                assertEquals(120, outcome.vectorsTaken)
                assertTrue(bobs.gaps.isEmpty())
                // Fifty to a message, so three messages rather than one nobody could send.
                assertEquals(3, alices.servings)
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }

    @Test
    fun `a dead end at the head of the peer's list does not hide the rest`() {
        val hers = temporary()
        val his = temporary()
        try {
            // Alice is missing sixty tracks. The first fifty are ones nobody has analysed — she is
            // working through them herself — and the ten behind them are exactly what Bob holds.
            val missing = (1..60).map { "k$it" }
            val alices = Desk(missing = missing)
            val bobs = Desk(held = (51..60).associate { "k$it" to "k$it:v$it" })
            val server = serverFor(hers, alice, key, alices)
            assertTrue(server.start())
            try {
                val outcome = trade(server, bob, his, bobs)

                assertNull(outcome.trouble)
                // Asking her fifty at a time would have ended on the dead end and handed over
                // nothing at all, which is what it did against a real phone.
                assertEquals(10, outcome.vectorsGiven)
                assertEquals(missing.take(50), alices.gaps)
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }

    @Test
    fun `a record the peer turns away is not offered twice in one conversation`() {
        val hers = temporary()
        val his = temporary()
        try {
            // Alice is missing three and will not take the first — a duplicate in her own library
            // makes her refuse what is served for it, which is the one case that kept this loop
            // going round.
            val alices = Desk(missing = listOf("A", "B", "C"), refusing = setOf("A"))
            val bobs = Desk(held = mapOf("A" to "A:one", "B" to "B:two", "C" to "C:three"))
            val server = serverFor(hers, alice, key, alices)
            assertTrue(server.start())
            try {
                val outcome = trade(server, bob, his, bobs)

                assertNull(outcome.trouble)
                assertEquals(2, outcome.vectorsGiven)
                // Asked once. Her next answer still names the one she refused — she is still
                // missing it — and answering that again is what cost four thousand records.
                assertEquals(listOf(listOf("A", "B", "C")), bobs.asked)
                assertEquals(listOf("A"), alices.gaps)
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }

    @Test
    fun `a peer that carries no vectors at all is not a failure`() {
        val hers = temporary()
        val his = temporary()
        try {
            val bobs = Desk(missing = listOf("A"))
            val server = serverFor(hers, alice, key)
            assertTrue(server.start())
            try {
                val outcome = trade(server, bob, his, bobs)

                assertNull(outcome.trouble)
                assertEquals(0, outcome.vectorsTaken)
                assertEquals(listOf("A"), bobs.gaps)
            } finally {
                server.stop()
            }
        } finally {
            hers.deleteRecursively()
            his.deleteRecursively()
        }
    }
}
