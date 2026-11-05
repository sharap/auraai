package music.ai.recommend.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One line of a sync file: a batch of records, with the envelope they travel in.
 *
 * A batch rather than a record per line so that the cost of a nonce and an authentication tag is
 * paid per flush instead of per listen, and one line rather than one file so that a file copied
 * while it was being written loses that batch and nothing else.
 *
 * [sealing] is `none` for the moment and the records ride in the clear. The field exists from the
 * first version anyway: the three it takes to carry a sealed batch are cheap now and a format
 * change later, at the point where the data has become worth keeping. When a key exists, the
 * records become ciphertext and [deviceId], [sequence] and [sealing] become its associated data,
 * so that a batch cannot be moved into another device's file or renumbered without the seal
 * failing.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
data class SyncBatch(
    /** Whose batch this is; also the name of the directory the file sits in. */
    val deviceId: String,
    /** That device's batch counter, so a batch missing from the middle shows up as a gap. */
    val sequence: Long,
    /** What sealed the records. [SEALING_NONE] until there is a key. */
    val sealing: String,
    val records: List<JsonObject>
) {

    fun encode(): String {
        val json = JsonObject()
        json.addProperty("d", deviceId)
        json.addProperty("n", sequence)
        json.addProperty("e", sealing)
        json.add("ct", JsonArray().apply { records.forEach { add(it) } })
        return json.toString()
    }

    companion object {

        /** Not sealed: the records are there to read. */
        const val SEALING_NONE = "none"

        /**
         * @return the batch, or null when the line cannot be used — it is malformed, or truncated
         *   because the file was copied mid-write, or sealed in a way this version does not know.
         *   All three mean the same thing to a reader: skip it, and look again next time.
         */
        fun decode(line: String): SyncBatch? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            return try {
                val json = JsonParser.parseString(trimmed) as? JsonObject ?: return null
                val deviceId = json.get("d")?.asString ?: return null
                if (deviceId.isEmpty()) return null
                val sealing = json.get("e")?.asString ?: return null
                if (sealing != SEALING_NONE) return null
                val array = json.getAsJsonArray("ct") ?: return null
                SyncBatch(
                    deviceId = deviceId,
                    sequence = json.get("n")?.asLong ?: return null,
                    sealing = sealing,
                    records = array.mapNotNull { it as? JsonObject }
                )
            } catch (e: RuntimeException) {
                // Gson throws on malformed JSON and on a field that is not the type asked for;
                // a truncated last line arrives here too.
                null
            }
        }
    }
}
