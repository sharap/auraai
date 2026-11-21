package music.ai.recommend.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What two devices say to each other over a [PeerLink], and nothing about how they say it.
 *
 * The conversation is deliberately the same shape as the folder it replaces. A device owns one
 * directory of day files and may only ever append to it; everything else it holds is a copy of
 * somebody else's. So there are only two things to ask — *what have you got of your own* and *what
 * have you got of mine* — and two things to do about the answers: take the bytes that are missing
 * here, and offer the bytes that are missing there. Offsets are bytes, because the files are
 * append-only and a byte count is the one cursor both sides can agree on without trusting each
 * other's line counting.
 *
 * That shape is why this is a small piece of work rather than a sync engine. Nothing above it
 * changes: the same batches, the same readers, the same merge, the same record of how far each
 * file was read. The socket fills a local copy of the other device's directory, and the machinery
 * that has been reading a shared folder for months goes on reading a folder.
 *
 * Every number and every path that arrives here is somebody else's claim, so each is checked before
 * it is used: a path has to be shaped like one of the writers' own ([DayFile.isOurs], which is also
 * what keeps `..` and absolute paths out), an offset cannot be negative, and a chunk cannot be
 * larger than [MAX_CHUNK]. A message that fails any of it is not an error to report back and
 * recover from — it is a peer doing something it has no business doing, and the connection ends.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object PeerProtocol {

    /**
     * The port a device listens on by default.
     *
     * Unregistered, above the ephemeral range on neither platform, and fixed rather than announced
     * only through discovery so that an owner on a network that eats broadcasts can type an address
     * and have it work.
     */
    const val PORT = 54_333

    /**
     * The most bytes of a file in one message. Large enough that a megabyte of vectors is four
     * round trips rather than forty, small enough that a frame fits [PeerLink.MAX_FRAME] with its
     * base64 expansion and that a stalled transfer loses little.
     */
    const val MAX_CHUNK = 256 * 1024

    /** The most files one side will describe in a listing: a decade of day files for four kinds. */
    const val MAX_FILES = 10_000

    /**
     * The most records in one message, and therefore the most keys in a message whose *answer* is
     * records.
     *
     * Fifty records is about 185 KB of text, which fits a frame with room to spare after base64.
     * The asking side chops its own list into messages this size, so a library's worth is a few
     * dozen round trips down one connection rather than one message nobody could send.
     */
    const val MAX_RECORDS = 50

    /**
     * The most keys in a message that answers with keys.
     *
     * Twenty times [MAX_RECORDS], because a key is a name and not a vector — a thousand of them is
     * a hundred kilobytes — and because the one place this number matters is a device describing a
     * gap of thousands. Asking it fifty at a time means the first fifty decide everything: if the
     * other side holds none of them the round ends, and the thousands behind them are never
     * reached. Found exactly that way, against a phone re-analysing its own library while the
     * desktop had the rest of it in hand.
     */
    const val MAX_KEYS = 1_000

    /** The most characters one record or key may be; a vector of 512 floats is about 3.6 KB. */
    const val MAX_TEXT = 64 * 1024

    /** One file and how many bytes of it the speaker holds. */
    data class Held(val path: String, val bytes: Long)

    sealed class Request {
        /** Who is calling. Nothing rests on the name: the seal is what proves anything. */
        data class Hello(val deviceId: String) : Request()

        /** What have you got of your own? */
        object List : Request()

        /** Give me [max] bytes of your [path] from [at]. */
        data class Fetch(val path: String, val at: Long, val max: Int) : Request()

        /** What have you got of mine? */
        object Index : Request()

        /** Here are bytes of *my* directory, to put at [at] of [path]. */
        data class Put(val path: String, val at: Long, val bytes: ByteArray) : Request() {
            // A data class compares a ByteArray by identity, which would make two identical
            // messages unequal and every test about this format compare the wrong thing.
            override fun equals(other: Any?): Boolean = other is Put &&
                path == other.path && at == other.at && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int =
                31 * (31 * path.hashCode() + at.hashCode()) + bytes.contentHashCode()
        }

        /**
         * How many vectors have you got, and how many of your tracks have none?
         *
         * Two numbers, and the cheap question that decides whether the expensive ones are worth
         * asking at all: neither side having gained or lost anything since the last exchange means
         * the last answer still stands. Without it, two devices that are simply missing the same
         * few tracks for ever would trade a few thousand keys every half minute to be told so.
         */
        object Vectors : Request()

        /** I have no vector for these tracks; send the ones you hold. */
        data class Want(val keys: kotlin.collections.List<String>) : Request()

        /** Which of your tracks have no vector? At most [max] of them. */
        data class Wanted(val max: Int) : Request()

        /** Here are records for tracks you said you lack. */
        data class Give(val records: kotlin.collections.List<String>) : Request()

        /** Nothing more; close cleanly rather than by timeout. */
        object Bye : Request()
    }

    sealed class Reply {
        data class Hello(val deviceId: String) : Reply()
        data class Listing(val files: kotlin.collections.List<Held>) : Reply()
        data class Chunk(val at: Long, val bytes: ByteArray, val eof: Boolean) : Reply() {
            override fun equals(other: Any?): Boolean = other is Chunk &&
                at == other.at && eof == other.eof && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int =
                31 * (31 * at.hashCode() + eof.hashCode()) + bytes.contentHashCode()
        }

        data class Index(val files: kotlin.collections.List<Held>) : Reply()

        /** How many bytes of the file the other side holds now, so the sender can check. */
        data class Stored(val bytes: Long) : Reply()

        /**
         * How many vectors the speaker holds, how many of its tracks have none, and how many of
         * them it can name by their sound.
         *
         * The third number is why a settled pair looks again: prints change what a key means
         * without changing any total.
         */
        data class Vectors(val have: Int, val lacks: Int, val named: Int = 0) : Reply()

        /** The records asked for, which may be fewer than the keys or none at all. */
        data class Records(val records: kotlin.collections.List<String>) : Reply()

        /** The keys of tracks the speaker has no vector for. */
        data class Keys(val keys: kotlin.collections.List<String>) : Reply()

        /** How many of the records handed over were stored. */
        data class Took(val stored: Int) : Reply()

        /**
         * A refusal, with a word about why for the log. Sent only for things a well-behaved peer
         * can run into — a file that moved, an offset that no longer lines up — never for a
         * malformed message, which ends the connection instead.
         */
        data class No(val why: String) : Reply()
    }

    fun write(request: Request): String = JsonObject().apply {
        when (request) {
            is Request.Hello -> {
                addProperty("q", "hello")
                addProperty("d", request.deviceId)
            }
            Request.List -> addProperty("q", "list")
            is Request.Fetch -> {
                addProperty("q", "fetch")
                addProperty("p", request.path)
                addProperty("at", request.at)
                addProperty("max", request.max)
            }
            Request.Index -> addProperty("q", "index")
            is Request.Put -> {
                addProperty("q", "put")
                addProperty("p", request.path)
                addProperty("at", request.at)
                addProperty("b", Base64Text.of(request.bytes))
            }
            Request.Vectors -> addProperty("q", "vectors")
            is Request.Want -> {
                addProperty("q", "want")
                add("k", texts(request.keys))
            }
            is Request.Wanted -> {
                addProperty("q", "wanted")
                addProperty("max", request.max)
            }
            is Request.Give -> {
                addProperty("q", "give")
                add("v", texts(request.records))
            }
            Request.Bye -> addProperty("q", "bye")
        }
    }.toString()

    /** @return the request, or null for anything that is not one. The caller then hangs up. */
    fun readRequest(text: String): Request? {
      return try {
        val json = JsonParser.parseString(text) as? JsonObject ?: return null
        when (json.get("q")?.asString) {
            "hello" -> json.get("d")?.asString?.takeIf { it.isNotEmpty() }?.let { Request.Hello(it) }
            "list" -> Request.List
            "fetch" -> {
                val path = path(json) ?: return null
                val at = offset(json) ?: return null
                val max = json.get("max")?.asInt ?: return null
                if (max <= 0 || max > MAX_CHUNK) return null
                Request.Fetch(path, at, max)
            }
            "index" -> Request.Index
            "put" -> {
                val path = path(json) ?: return null
                val at = offset(json) ?: return null
                val bytes = Base64Text.toBytes(json.get("b")?.asString ?: return null) ?: return null
                if (bytes.isEmpty() || bytes.size > MAX_CHUNK) return null
                Request.Put(path, at, bytes)
            }
            "vectors" -> Request.Vectors
            "want" -> texts(json, "k")?.let { Request.Want(it) }
            "wanted" -> {
                val max = json.get("max")?.asInt ?: return null
                if (max <= 0) return null
                // Their number, kept within ours: a peer asking for a hundred thousand keys gets
                // as many as this version is willing to put in one message.
                Request.Wanted(minOf(max, MAX_KEYS))
            }
            "give" -> texts(json, "v")?.let { Request.Give(it) }
            "bye" -> Request.Bye
            else -> null
        }
      } catch (e: RuntimeException) {
        // Gson throws on a number that is a string, a string that is an object, and so on.
        null
      }
    }

    fun write(reply: Reply): String = JsonObject().apply {
        when (reply) {
            is Reply.Hello -> {
                addProperty("r", "hello")
                addProperty("d", reply.deviceId)
            }
            is Reply.Listing -> {
                addProperty("r", "list")
                add("f", held(reply.files))
            }
            is Reply.Chunk -> {
                addProperty("r", "fetch")
                addProperty("at", reply.at)
                addProperty("b", Base64Text.of(reply.bytes))
                addProperty("eof", reply.eof)
            }
            is Reply.Index -> {
                addProperty("r", "index")
                add("f", held(reply.files))
            }
            is Reply.Stored -> {
                addProperty("r", "put")
                addProperty("n", reply.bytes)
            }
            is Reply.Vectors -> {
                addProperty("r", "vectors")
                addProperty("have", reply.have)
                addProperty("lacks", reply.lacks)
                addProperty("named", reply.named)
            }
            is Reply.Records -> {
                addProperty("r", "records")
                add("v", texts(reply.records))
            }
            is Reply.Keys -> {
                addProperty("r", "keys")
                add("k", texts(reply.keys, MAX_KEYS))
            }
            is Reply.Took -> {
                addProperty("r", "took")
                addProperty("n", reply.stored)
            }
            is Reply.No -> {
                addProperty("r", "no")
                addProperty("why", reply.why)
            }
        }
    }.toString()

    /** @return the reply, or null for anything that is not one. The caller then hangs up. */
    fun readReply(text: String): Reply? {
      return try {
        val json = JsonParser.parseString(text) as? JsonObject ?: return null
        when (json.get("r")?.asString) {
            "hello" -> json.get("d")?.asString?.takeIf { it.isNotEmpty() }?.let { Reply.Hello(it) }
            "list" -> files(json)?.let { Reply.Listing(it) }
            "index" -> files(json)?.let { Reply.Index(it) }
            "fetch" -> {
                val at = offset(json) ?: return null
                val bytes = Base64Text.toBytes(json.get("b")?.asString ?: return null) ?: return null
                if (bytes.size > MAX_CHUNK) return null
                Reply.Chunk(at, bytes, json.get("eof")?.asBoolean ?: return null)
            }
            "put" -> {
                val bytes = json.get("n")?.asLong ?: return null
                if (bytes < 0) return null
                Reply.Stored(bytes)
            }
            "vectors" -> {
                val have = json.get("have")?.asInt ?: return null
                val lacks = json.get("lacks")?.asInt ?: return null
                // Absent from a device built before prints existed, which is not a refusal to
                // answer: it names nothing by sound, and zero says exactly that.
                val named = json.get("named")?.asInt ?: 0
                if (have < 0 || lacks < 0 || named < 0) return null
                Reply.Vectors(have, lacks, named)
            }
            "records" -> texts(json, "v")?.let { Reply.Records(it) }
            "keys" -> texts(json, "k", MAX_KEYS)?.let { Reply.Keys(it) }
            "took" -> {
                val stored = json.get("n")?.asInt ?: return null
                if (stored < 0) return null
                Reply.Took(stored)
            }
            "no" -> Reply.No(json.get("why")?.asString.orEmpty())
            else -> null
        }
      } catch (e: RuntimeException) {
        null
      }
    }

    private fun held(files: kotlin.collections.List<Held>) = com.google.gson.JsonArray().apply {
        for (file in files.take(MAX_FILES)) {
            add(JsonObject().apply {
                addProperty("p", file.path)
                addProperty("n", file.bytes)
            })
        }
    }

    private fun files(json: JsonObject): kotlin.collections.List<Held>? {
        val array = json.getAsJsonArray("f") ?: return null
        if (array.size() > MAX_FILES) return null
        val out = ArrayList<Held>(array.size())
        for (element in array) {
            val item = element as? JsonObject ?: return null
            val path = path(item) ?: return null
            val bytes = item.get("n")?.asLong ?: return null
            if (bytes < 0) return null
            out.add(Held(path, bytes))
        }
        return out
    }

    private fun texts(values: kotlin.collections.List<String>, most: Int = MAX_RECORDS) =
        com.google.gson.JsonArray().apply {
            for (text in values.take(most)) add(text)
        }

    /**
     * Keys or records as they arrived: a list of strings, each of a size this version is willing to
     * hold, and no more of them than it is willing to answer.
     *
     * What is *in* them is not checked here and deliberately so. A key and a record are judged
     * where every other key and record is judged — by the reader and the terms — and a protocol
     * that also had an opinion about them would be a second place for that opinion to be wrong.
     */
    private fun texts(
        json: JsonObject,
        name: String,
        most: Int = MAX_RECORDS
    ): kotlin.collections.List<String>? {
        val array = json.getAsJsonArray(name) ?: return null
        if (array.size() > most) return null
        val out = ArrayList<String>(array.size())
        for (element in array) {
            val primitive = element as? com.google.gson.JsonPrimitive ?: return null
            if (!primitive.isString) return null
            val text = primitive.asString
            if (text.isEmpty() || text.length > MAX_TEXT) return null
            out.add(text)
        }
        return out
    }

    /**
     * A path is only ever one of the shapes this program's own writers produce, which is what
     * keeps `..`, absolute paths and a peer's imagination out of the file system.
     */
    private fun path(json: JsonObject): String? =
        json.get("p")?.asString?.takeIf { DayFile.isOurs(it) }

    private fun offset(json: JsonObject): Long? =
        json.get("at")?.asLong?.takeIf { it >= 0L }
}
