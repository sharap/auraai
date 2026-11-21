package music.ai.recommend.sync

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What seals a batch of records and opens one.
 *
 * An interface, and not because a second implementation is expected: it is so that everything
 * above it — the writers, the readers, the coordinator — can be given one or not given one, and
 * work either way. A folder written before the owner set a phrase holds plain batches and goes on
 * being readable for ever.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface Sealing {

    /** What goes in the envelope's `e`, so a reader knows what it is looking at. */
    val name: String

    /**
     * A short name for the key in use, telling nothing about it.
     *
     * Kept in the sync state, so that changing the phrase can undo the reading: a batch that could
     * not be opened is marked read and never offered again, and without this the day the owner
     * fixes a typo on one device, everything written before it would stay unreadable for ever.
     */
    val fingerprint: String

    /** @return the sealed text and the nonce it was sealed under, or null if it could not. */
    fun seal(deviceId: String, sequence: Long, plaintext: String): Sealed?

    /** @return the plaintext, or null when it does not open — a different phrase, or a changed line. */
    fun open(deviceId: String, sequence: Long, nonce: String, sealed: String): String?

    data class Sealed(val nonce: String, val text: String)
}

/**
 * AES-256-GCM over a key derived from a phrase the owner types on both devices.
 *
 * ### Why the payload and not the channel
 *
 * The folder is the weak place, not the wire. Syncthing already carries the bytes under mutual TLS
 * pinned to each device's key, and the sync folder on Android is **not** app-private: any app
 * holding the storage permission can read it. Sealing the records means every transport is a dumb
 * pipe — Syncthing and its relays, a socket on a café's network, a USB stick — and the question of
 * who is trusted to carry them stops needing an answer. It is also what makes the local-network
 * transport a small piece of work rather than a security project.
 *
 * ### The key
 *
 * One phrase, typed by the owner on each device, and a key derived from it **per writing device**:
 * PBKDF2-HMAC-SHA256, two hundred thousand rounds, salted with that device's id — which is a
 * random 128-bit value, so it is a proper salt and nothing can be precomputed against this app in
 * general. A reader derives the key for the device whose file it is reading, and keeps it for the
 * run.
 *
 * Deriving per device buys something beyond the salt: a key is used by exactly one writer, so its
 * nonces cannot collide with another device's. The nonce is the device's first four bytes followed
 * by the batch counter, and that counter never repeats for a given device — which is the one thing
 * AES-GCM cannot forgive.
 *
 * The envelope's `d`, `n` and `e` are the associated data, so a batch cannot be moved into another
 * device's file, renumbered, or relabelled as unsealed without the seal failing.
 *
 * ### What this is not
 *
 * It is not pairing. A phrase typed by a person is weaker than a key exchanged between devices and
 * held in hardware, and it is here because it works today over a folder both devices already
 * share. When the local-network transport arrives, pairing arrives with it — a code shown on one
 * device and read on the other, a key in the Android keystore — and this stays as the fallback for
 * a folder that is carried rather than connected.
 *
 * It also does not prove that nothing was removed from the end of a file. No scheme does without
 * asking the other device what it believes it wrote; gaps in the middle are caught by `n`, and the
 * tail is accepted as a known limit.
 */
class GcmSealing(private val phrase: String) : Sealing {

    override val name: String get() = NAME

    override val fingerprint: String by lazy { fingerprintOf(phrase) }

    /** One derivation per device per run; it is deliberately expensive. */
    private val keys = HashMap<String, SecretKeySpec>()

    private fun keyFor(deviceId: String): SecretKeySpec = synchronized(keys) {
        keys.getOrPut(deviceId) {
            SecretKeySpec(
                pbkdf2(phrase.toByteArray(Charsets.UTF_8), deviceId.toByteArray(Charsets.UTF_8)),
                "AES"
            )
        }
    }

    override fun seal(deviceId: String, sequence: Long, plaintext: String): Sealing.Sealed? = try {
        val nonce = nonceFor(deviceId, sequence)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(deviceId), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(deviceId, sequence))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Sealing.Sealed(Base64Text.of(nonce), Base64Text.of(sealed))
    } catch (e: Exception) {
        // A device without AES-GCM, or without the memory to derive a key. Nothing is written
        // rather than written in the clear: the owner asked for this.
        null
    }

    override fun open(deviceId: String, sequence: Long, nonce: String, sealed: String): String? {
        return try {
            val carried = Base64Text.toBytes(nonce) ?: return null
            // The nonce is derived here too and the carried one has to match it. It is not a
            // secret and tampering with it would fail the seal anyway; checking it keeps one way
            // of naming a batch instead of two.
            if (!carried.contentEquals(nonceFor(deviceId, sequence))) return null
            val bytes = Base64Text.toBytes(sealed) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyFor(deviceId), GCMParameterSpec(TAG_BITS, carried))
            cipher.updateAAD(associatedData(deviceId, sequence))
            String(cipher.doFinal(bytes), Charsets.UTF_8)
        } catch (e: Exception) {
            // The usual reason is the other device's phrase differing from this one's, and the
            // next is a line that was changed on the way. Neither is recoverable here.
            null
        }
    }

    private fun associatedData(deviceId: String, sequence: Long): ByteArray =
        "$deviceId|$sequence|$NAME".toByteArray(Charsets.UTF_8)

    /**
     * Twelve bytes: four of the device's id, then the batch counter.
     *
     * Which cannot repeat for one key, because the key belongs to one device and that device's
     * counter only ever rises — a number spent on a write that failed is not reused, it leaves a
     * gap, and a gap is how the other side tells a lost batch from a reboot.
     */
    private fun nonceFor(deviceId: String, sequence: Long): ByteArray {
        val nonce = ByteArray(12)
        val head = hexBytes(deviceId, 4)
        head.copyInto(nonce)
        for (index in 0 until 8) {
            nonce[4 + index] = ((sequence ushr ((7 - index) * 8)) and 0xFF).toByte()
        }
        return nonce
    }

    companion object {

        const val NAME = "a256gcm"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32

        /**
         * Rounds of the key derivation. Paid once per device per run, and deliberately heavy: the
         * phrase is the only secret, and the folder it protects can be read by anything on the
         * phone's shared storage.
         */
        const val ROUNDS = 200_000

        /**
         * A name for the phrase in use, short and telling nothing about it.
         *
         * Kept in the sync state so that a change of phrase can undo the reading, exactly as a
         * change in what a vector is believed on does: a batch that could not be opened is marked
         * read and never offered again, so without this the day the owner fixes a typo on one
         * device, everything written before it would stay unreadable for ever.
         */
        fun fingerprintOf(phrase: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("aura-seal-v1".toByteArray(Charsets.UTF_8))
            val bytes = digest.digest(phrase.toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }

        /** The first [count] bytes of a hex string, or its characters where it is not hex. */
        private fun hexBytes(text: String, count: Int): ByteArray {
            val out = ByteArray(count)
            for (index in 0 until count) {
                val at = index * 2
                val high = text.getOrNull(at)?.digitToIntOrNull(16)
                val low = text.getOrNull(at + 1)?.digitToIntOrNull(16)
                out[index] = if (high != null && low != null) {
                    ((high shl 4) or low).toByte()
                } else {
                    text.getOrNull(index)?.code?.toByte() ?: 0
                }
            }
            return out
        }

        /**
         * PBKDF2-HMAC-SHA256, written out rather than taken from the platform.
         *
         * `SecretKeyFactory` only knows `PBKDF2WithHmacSHA256` from API 26, against this app's 23,
         * and a key derived one way on one device and another way on the other is two keys. HMAC
         * itself is everywhere. The test checks this against the JVM's own implementation, which
         * the desktop has.
         */
        fun pbkdf2(
            password: ByteArray,
            salt: ByteArray,
            rounds: Int = ROUNDS,
            bytes: Int = KEY_BYTES
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val length = mac.macLength
            val blocks = (bytes + length - 1) / length
            val out = ByteArray(blocks * length)
            for (block in 1..blocks) {
                mac.update(salt)
                val counter = byteArrayOf(
                    (block ushr 24).toByte(),
                    (block ushr 16).toByte(),
                    (block ushr 8).toByte(),
                    block.toByte()
                )
                var round = mac.doFinal(counter)
                val folded = round.copyOf()
                for (index in 2..rounds) {
                    round = mac.doFinal(round)
                    for (at in folded.indices) {
                        folded[at] = (folded[at].toInt() xor round[at].toInt()).toByte()
                    }
                }
                folded.copyInto(out, (block - 1) * length)
            }
            return out.copyOf(bytes)
        }
    }
}
