package music.ai.recommend.sync

/**
 * Base64, by hand.
 *
 * Neither platform's own will do: `java.util.Base64` arrived in API 26 against this app's minimum
 * of 23, and `android.util.Base64` does not exist off Android. Thirty lines settle it for good,
 * and a line of a sync file has to mean the same thing on both sides down to the character.
 *
 * Here rather than in whichever record needed it first, because three kinds of thing now travel as
 * base64 — a vector's floats, a sealed batch and its nonce — and three copies of this is three
 * chances for one of them to be subtly different.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
internal object Base64Text {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun of(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var at = 0
        while (at + 2 < bytes.size) {
            val word = ((bytes[at].toInt() and 0xFF) shl 16) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                (bytes[at + 2].toInt() and 0xFF)
            out.append(ALPHABET[(word ushr 18) and 0x3F])
            out.append(ALPHABET[(word ushr 12) and 0x3F])
            out.append(ALPHABET[(word ushr 6) and 0x3F])
            out.append(ALPHABET[word and 0x3F])
            at += 3
        }
        when (bytes.size - at) {
            1 -> {
                val word = (bytes[at].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(word ushr 18) and 0x3F])
                out.append(ALPHABET[(word ushr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val word = ((bytes[at].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(word ushr 18) and 0x3F])
                out.append(ALPHABET[(word ushr 12) and 0x3F])
                out.append(ALPHABET[(word ushr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
        }

    /** @return the bytes, or null for anything that is not well-formed base64. */
    fun toBytes(text: String): ByteArray? {
        val body = text.trimEnd('=')
        if (text.length % 4 != 0 || text.length - body.length > 2) return null
        val out = ByteArray(body.length * 3 / 4)
        var bits = 0
        var held = 0
        var written = 0
        for (character in body) {
            val value = ALPHABET.indexOf(character)
            if (value < 0) return null
            bits = (bits shl 6) or value
            held += 6
            if (held >= 8) {
                held -= 8
                out[written++] = ((bits ushr held) and 0xFF).toByte()
            }
        }
        return if (written == out.size) out else null
    }
}
