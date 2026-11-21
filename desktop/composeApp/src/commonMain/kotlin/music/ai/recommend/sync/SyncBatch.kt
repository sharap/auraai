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
 * [sealing] says what to do with `ct`: `none` and the records are there to read, `a256gcm` and
 * they are ciphertext with a nonce beside them. A folder written before the owner set a phrase
 * holds plain batches and stays readable for ever, which is the whole reason the field was there
 * from the first version.
 *
 * [deviceId], [sequence] and [sealing] are the sealed batch's associated data, so a batch cannot
 * be moved into another device's file, renumbered, or relabelled as unsealed without the seal
 * failing. See [Sealing].
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
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

    /**
     * @param seal what to seal the records with, or null to write them in the clear.
     * @return the line, or null when sealing was asked for and could not be done. Nothing is
     *   written then rather than written in the clear — the owner asked for this — and the batch
     *   number is not spent, so the records are offered again next pass.
     */
    fun encode(seal: Sealing? = null): String? {
        val json = JsonObject()
        json.addProperty("d", deviceId)
        json.addProperty("n", sequence)
        val plain = JsonArray().apply { records.forEach { add(it) } }
        if (seal == null) {
            json.addProperty("e", SEALING_NONE)
            json.add("ct", plain)
        } else {
            val sealed = seal.seal(deviceId, sequence, plain.toString()) ?: return null
            json.addProperty("e", seal.name)
            json.addProperty("iv", sealed.nonce)
            json.addProperty("ct", sealed.text)
        }
        return json.toString()
    }

    companion object {

        /** Not sealed: the records are there to read. */
        const val SEALING_NONE = "none"

        /**
         * What sealed a line, without opening it: `none`, a name this version may or may not know,
         * or null for a line that is not one of ours at all.
         *
         * For telling the two reasons a line was no use apart. A line that will not parse is
         * damage or a half-written tail; a line that parses and is sealed in a way this device
         * cannot open is almost always the owner having typed a different phrase on the other
         * device — and that one must be said out loud, because it is the only way this whole
         * feature fails, and it fails silently.
         */
        fun sealingOf(line: String): String? = try {
            (JsonParser.parseString(line.trim()) as? JsonObject)?.get("e")?.asString
        } catch (e: RuntimeException) {
            null
        }

        /**
         * @param seal what can open a sealed batch, or null for a reader that has no phrase.
         * @return the batch, or null when the line cannot be used — malformed, truncated because
         *   the file was copied mid-write, sealed in a way this version does not know, or sealed
         *   under a phrase this device does not have. All of them mean the same thing to a reader:
         *   skip it, and look again next time.
         */
        fun decode(line: String, seal: Sealing? = null): SyncBatch? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            return try {
                val json = JsonParser.parseString(trimmed) as? JsonObject ?: return null
                val deviceId = json.get("d")?.asString ?: return null
                if (deviceId.isEmpty()) return null
                val sealing = json.get("e")?.asString ?: return null
                val sequence = json.get("n")?.asLong ?: return null
                val records = when {
                    sealing == SEALING_NONE ->
                        (json.getAsJsonArray("ct") ?: return null).mapNotNull { it as? JsonObject }
                    seal == null || seal.name != sealing -> return null
                    else -> {
                        val opened = seal.open(
                            deviceId = deviceId,
                            sequence = sequence,
                            nonce = json.get("iv")?.asString ?: return null,
                            sealed = json.get("ct")?.asString ?: return null
                        ) ?: return null
                        (JsonParser.parseString(opened) as? JsonArray ?: return null)
                            .mapNotNull { it as? JsonObject }
                    }
                }
                SyncBatch(
                    deviceId = deviceId,
                    sequence = sequence,
                    sealing = sealing,
                    records = records
                )
            } catch (e: RuntimeException) {
                // Gson throws on malformed JSON and on a field that is not the type asked for;
                // a truncated last line arrives here too.
                null
            }
        }
    }
}
