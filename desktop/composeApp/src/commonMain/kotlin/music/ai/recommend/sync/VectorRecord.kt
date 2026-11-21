package music.ai.recommend.sync

import com.google.gson.JsonObject

/**
 * One track's embedding as it travels, with everything a receiving device needs in order to decide
 * whether it may use it at all.
 *
 * Carrying vectors is what makes a second device cheap: a library takes hours to analyse and the
 * result is a pure function of the file, the excerpt, the decoder and the model. So the payload is
 * a cache entry, and everything that went into computing it rides along as the terms on which it
 * may be believed. A vector accepted on looser terms than these is not a small error — it is a
 * track sitting in the wrong place in the similarity space, with nothing to show that it is wrong.
 *
 * The floats are base64 of little-endian fp32 rather than a JSON array of numbers: 2.7 KB a record
 * against about 7 KB, and a library of four thousand tracks is the normal size of this file.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class VectorRecord(
    val track: TrackKey,
    /** The start of the ten seconds that were listened to; see `Excerpt.startMs`. */
    val excerptStartMs: Long,
    /** What turned the file into samples, e.g. `jlayer-1.0.1`. */
    val decoder: String,
    /** sha256 of the model file that produced it, which is the only exact name a variant has. */
    val model: String,
    /** Bumped whenever anything that changes the value of an embedding changes. */
    val analysisVersion: Int,
    val vector: FloatArray
) {

    // Written out rather than taken from a data class: a data class compares a FloatArray by
    // identity, so two records holding the same 512 numbers would not be equal, and every test
    // about this format would be comparing the wrong thing.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorRecord) return false
        return track == other.track &&
            excerptStartMs == other.excerptStartMs &&
            decoder == other.decoder &&
            model == other.model &&
            analysisVersion == other.analysisVersion &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = track.hashCode()
        result = 31 * result + excerptStartMs.hashCode()
        result = 31 * result + decoder.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + analysisVersion
        result = 31 * result + vector.contentHashCode()
        return result
    }

    override fun toString(): String =
        "VectorRecord(${track.artist} - ${track.title}, start=$excerptStartMs, $decoder, " +
            "v$analysisVersion, ${vector.size} floats)"

    fun toJson(): JsonObject = JsonObject().apply {
        add("k", track.written())
        addProperty("s", excerptStartMs)
        addProperty("dec", decoder)
        addProperty("m", model)
        addProperty("av", analysisVersion)
        addProperty("f", encodeFloats(vector))
    }

    companion object {

        /**
         * @return the record, or null if the line does not hold one. A reader that meets something
         *   it cannot parse drops that record and goes on, exactly as with a listen: one bad line
         *   from a future version or a half-written file must not cost the rest of the file.
         */
        fun fromJson(json: JsonObject): VectorRecord? {
            return try {
                val key = json.getAsJsonObject("k") ?: return null
                val track = TrackKey.read(key) ?: return null
                val decoder = json.get("dec")?.asString ?: return null
                val model = json.get("m")?.asString ?: return null
                if (decoder.isEmpty() || model.isEmpty()) return null
                val floats = decodeFloats(json.get("f")?.asString ?: return null) ?: return null
                if (floats.isEmpty()) return null
                // Nothing but zeros is not a vector. Until the refusal of an unreadable file was
                // given a record of its own, a device that could not decode a track stored 512
                // zeros for it and offered them round like any other vector; a device that could
                // read the file would have taken that for an analysis and never looked at the
                // track again. The lines are still out there in the folder, so they are turned
                // away here rather than only at the far end of the writing side.
                if (floats.all { it == 0f }) return null
                VectorRecord(
                    track = track,
                    excerptStartMs = json.get("s")?.asLong ?: return null,
                    decoder = decoder,
                    model = model,
                    analysisVersion = json.get("av")?.asInt ?: return null,
                    vector = floats
                )
            } catch (e: RuntimeException) {
                // Gson throws on a number that is a string, a string that is an object, and so on.
                null
            }
        }

        /**
         * Base64 of the floats, little-endian, four bytes each.
         *
         * Written here rather than taken from a library because neither platform has one both can
         * use: `java.util.Base64` arrived in API 26 against this app's minimum of 23, and
         * `android.util.Base64` does not exist off Android. Thirty lines settle it for good, and a
         * wire format is the last place to want a surprise from a dependency.
         */
        fun encodeFloats(values: FloatArray): String {
            val bytes = ByteArray(values.size * 4)
            for ((index, value) in values.withIndex()) {
                val bits = value.toRawBits()
                val at = index * 4
                bytes[at] = (bits and 0xFF).toByte()
                bytes[at + 1] = ((bits ushr 8) and 0xFF).toByte()
                bytes[at + 2] = ((bits ushr 16) and 0xFF).toByte()
                bytes[at + 3] = ((bits ushr 24) and 0xFF).toByte()
            }
            return Base64Text.of(bytes)
        }

        /** @return the floats, or null when the text is not base64 of a whole number of them. */
        fun decodeFloats(text: String): FloatArray? {
            val bytes = Base64Text.toBytes(text) ?: return null
            if (bytes.size % 4 != 0) return null
            return FloatArray(bytes.size / 4) { index ->
                val at = index * 4
                val bits = (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 3].toInt() and 0xFF) shl 24)
                Float.fromBits(bits)
            }
        }
    }
}
