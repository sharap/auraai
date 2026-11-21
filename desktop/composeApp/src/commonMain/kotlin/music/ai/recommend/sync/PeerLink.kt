package music.ai.recommend.sync

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Two devices talking to each other directly, with every frame sealed.
 *
 * This layer knows nothing about sync. It takes an input and an output stream — a socket's, a
 * test's pipe's — and turns them into a sequence of messages that cannot be read, forged or
 * reordered by anything else on the network. What the messages mean is [PeerProtocol]'s business.
 *
 * ### Why the frames are sealed when the payload already is
 *
 * The records inside are sealed already, so nothing an eavesdropper picks up here tells them what
 * anyone listened to. What they would learn from an unsealed channel is the shape of it: the device
 * ids, how many files each holds, which days they are named for, how many bytes move and when. On
 * a café's network that is a presence log, and it costs about thirty lines to not hand it over.
 *
 * What this is **not** is a second place where trust is decided. The payload's seal is what makes
 * a record believable; this seal only makes the conversation private. So there is deliberately no
 * fallback: a frame that will not open ends the conversation. Breaking the protected attempt and
 * letting the client retry in the clear is the classic way to turn a secure protocol into an
 * insecure one, and the way not to have that bug is to have no second path at all.
 *
 * ### The keys
 *
 * The owner's phrase gives the long-term key — PBKDF2-HMAC-SHA256 as [GcmSealing] derives its own,
 * but salted with this transport's own name, so that the key that protects a conversation is not
 * the key that seals a device's records. It is expensive by design, so a caller derives it once and
 * keeps it; [keyFrom] is where that happens.
 *
 * Each connection then gets a key of its own: both sides send sixteen random bytes in the clear and
 * the session key is `HMAC(phraseKey, "aura-session-v1" || clientNonce || serverNonce)`. That is
 * what makes the counters safe to use as nonces — a fresh key per connection means a counter that
 * starts at zero every time cannot repeat a nonce under the same key, which is the one thing
 * AES-GCM does not forgive. Each direction counts separately and says which it is in the nonce, so
 * the two sides cannot collide with each other either.
 *
 * A frame's nonce and its counter are authenticated rather than sent: the receiver knows what the
 * next counter must be, so a frame replayed, dropped or swapped with another does not open.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class PeerLink private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val sessionKey: SecretKeySpec,
    /** 0 for the side that connected, 1 for the side that accepted. */
    private val sending: Byte,
    private val receiving: Byte
) {

    private var sent = 0L
    private var received = 0L

    /** @return false when the frame could not be written, which is the end of the conversation. */
    fun send(text: String): Boolean = try {
        val sealed = seal(text, sending, sent) ?: return false
        sent++
        val length = sealed.size
        output.write(
            byteArrayOf(
                (length ushr 24).toByte(),
                (length ushr 16).toByte(),
                (length ushr 8).toByte(),
                length.toByte()
            )
        )
        output.write(sealed)
        output.flush()
        true
    } catch (e: Exception) {
        false
    }

    /**
     * @return the next message, or null for a stream that has ended or a frame that will not open.
     *   The caller treats both the same way: close the connection. There is nothing to negotiate.
     */
    fun receive(): String? {
      return try {
        val header = readExactly(4) ?: return null
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        // A length is checked before a buffer is allocated for it: the other end is not trusted
        // with how much memory this one spends, whatever it holds.
        if (length <= 0 || length > MAX_FRAME) return null
        val body = readExactly(length) ?: return null
        val text = open(body, receiving, received) ?: return null
        received++
        text
      } catch (e: Exception) {
        null
      }
    }

    private fun readExactly(count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var at = 0
        while (at < count) {
            val read = input.read(buffer, at, count - at)
            if (read < 0) return null
            at += read
        }
        return buffer
    }

    private fun seal(text: String, direction: Byte, counter: Long): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(TAG_BITS, nonce(direction, counter)))
        cipher.updateAAD(associatedData(direction, counter))
        cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    private fun open(body: ByteArray, direction: Byte, counter: Long): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(TAG_BITS, nonce(direction, counter)))
        cipher.updateAAD(associatedData(direction, counter))
        String(cipher.doFinal(body), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Twelve bytes: which direction, then the counter. Fresh session key, so it cannot repeat. */
    private fun nonce(direction: Byte, counter: Long): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = direction
        for (index in 0 until 8) {
            nonce[4 + index] = ((counter ushr ((7 - index) * 8)) and 0xFF).toByte()
        }
        return nonce
    }

    private fun associatedData(direction: Byte, counter: Long): ByteArray =
        "$AAD|$direction|$counter".toByteArray(Charsets.UTF_8)

    companion object {

        /** Bumped if a frame's shape changes. Sent in the clear, before there is a key to use. */
        const val VERSION = 1

        /**
         * The largest frame either side will read. A quarter of a megabyte of payload plus room:
         * the protocol's own messages are tiny and a chunk of a file is [PeerProtocol.MAX_CHUNK].
         */
        const val MAX_FRAME = 512 * 1024

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val AAD = "aura-frame-v1"
        private const val MAGIC = "AURA"
        private const val NONCE_BYTES = 16

        /**
         * The long-term key from the owner's phrase. Expensive — two hundred thousand rounds — so
         * derive it once per phrase and keep it; a connection costs nothing after that.
         *
         * Salted with this transport's name rather than with a device id, because both sides have
         * to arrive at the same key and neither is "the writer" of a conversation. A fixed salt
         * means a phrase has one transport key, which is also what lets a device talk to a peer it
         * has never heard of before — the thing a folder never had to do.
         */
        fun keyFrom(phrase: String): ByteArray =
            GcmSealing.pbkdf2(phrase.toByteArray(Charsets.UTF_8), SALT.toByteArray(Charsets.UTF_8))

        private const val SALT = "aura-transport-v1"

        /** The side that dialled: sends the magic and its nonce, then reads the other's. */
        fun connect(
            input: InputStream,
            output: OutputStream,
            phraseKey: ByteArray,
            random: SecureRandom = SecureRandom()
        ): PeerLink? {
          return try {
            val ours = ByteArray(NONCE_BYTES).also(random::nextBytes)
            output.write(MAGIC.toByteArray(Charsets.US_ASCII))
            output.write(byteArrayOf(VERSION.toByte()))
            output.write(ours)
            output.flush()
            val version = readByte(input) ?: return null
            if (version.toInt() != VERSION) return null
            val theirs = readFully(input, NONCE_BYTES) ?: return null
            PeerLink(input, output, sessionKey(phraseKey, ours, theirs), sending = 0, receiving = 1)
          } catch (e: Exception) {
            null
          }
        }

        /** The side that answered. Returns null for anything that is not this protocol. */
        fun accept(
            input: InputStream,
            output: OutputStream,
            phraseKey: ByteArray,
            random: SecureRandom = SecureRandom()
        ): PeerLink? {
          return try {
            val magic = readFully(input, MAGIC.length) ?: return null
            if (String(magic, Charsets.US_ASCII) != MAGIC) return null
            val version = readByte(input) ?: return null
            // An older or newer frame shape is not negotiated down to something weaker; the
            // connection simply ends.
            if (version.toInt() != VERSION) return null
            val theirs = readFully(input, NONCE_BYTES) ?: return null
            val ours = ByteArray(NONCE_BYTES).also(random::nextBytes)
            output.write(byteArrayOf(VERSION.toByte()))
            output.write(ours)
            output.flush()
            PeerLink(input, output, sessionKey(phraseKey, theirs, ours), sending = 1, receiving = 0)
          } catch (e: Exception) {
            null
          }
        }

        private fun sessionKey(
            phraseKey: ByteArray,
            clientNonce: ByteArray,
            serverNonce: ByteArray
        ): SecretKeySpec {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(phraseKey, "HmacSHA256"))
            mac.update("aura-session-v1".toByteArray(Charsets.UTF_8))
            mac.update(clientNonce)
            mac.update(serverNonce)
            return SecretKeySpec(mac.doFinal(), "AES")
        }

        private fun readByte(input: InputStream): Byte? {
            val read = input.read()
            return if (read < 0) null else read.toByte()
        }

        private fun readFully(input: InputStream, count: Int): ByteArray? {
            val buffer = ByteArray(count)
            var at = 0
            while (at < count) {
                val read = try {
                    input.read(buffer, at, count - at)
                } catch (e: EOFException) {
                    -1
                }
                if (read < 0) return null
                at += read
            }
            return buffer
        }
    }
}
