package music.ai.recommend.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Sealing a batch, and the things that must not open one.
 *
 * What is being protected is the folder at rest rather than the wire: Syncthing already carries
 * the bytes under mutual TLS, while the sync folder on Android is not app-private and any app with
 * the storage permission can read it. A listening history is a minute-by-minute record of when
 * somebody is awake, at home and out; the vectors are harmless but what keys them is an inventory
 * of a library. So the first assertion worth reading here is the dull one — that a sealed line
 * does not contain the title that went into it.
 */
class SealingTest {

    private val phone = "aa".repeat(16)
    private val desktop = "bb".repeat(16)
    private val phrase = "корвет уходит в море"

    private fun sealing(of: String = phrase) = GcmSealing(of)

    private fun record(title: String): JsonObject = PlayRecord(
        playedAt = 1_793_880_000_000L,
        playedMs = 180_000,
        durationMs = 200_000,
        track = TrackKey(null, 200_000, "мельница", title)
    ).toJson()

    private fun batch(device: String = phone, sequence: Long = 7, title: String = "ночная кобыла") =
        SyncBatch(device, sequence, GcmSealing.NAME, listOf(record(title)))

    @Test
    fun aSealedBatchOpensAgainAndNothingOfItShows() {
        val line = batch().encode(sealing())!!

        // Nothing readable is left in the line: not the track, not the artist, not the time.
        assertFalse(line, line.contains("кобыла"))
        assertFalse(line, line.contains("мельница"))
        assertFalse(line, line.contains("1793880000000"))
        // What does show is who wrote it and which batch it is, because the reader needs both
        // before it can open anything — a gap in the numbering is how a lost batch is noticed.
        val json = JsonParser.parseString(line).asJsonObject
        assertEquals(phone, json.get("d").asString)
        assertEquals(7L, json.get("n").asLong)
        assertEquals("a256gcm", json.get("e").asString)

        val back = SyncBatch.decode(line, sealing())
        assertEquals(1, back?.records?.size)
        assertEquals(record("ночная кобыла"), back!!.records.single())
    }

    @Test
    fun aLineWrittenBeforeThereWasAPhraseStaysReadable() {
        // The folder is years of listening. A phrase set today must not make what is already
        // there unreadable, so an unsealed line is read by a device with a phrase and by one
        // without, and goes on being read for ever.
        val plain = batch().copy(sealing = SyncBatch.SEALING_NONE).encode(null)!!
        assertTrue(plain.contains("кобыла"))
        assertEquals(1, SyncBatch.decode(plain, null)?.records?.size)
        assertEquals(1, SyncBatch.decode(plain, sealing())?.records?.size)
    }

    @Test
    fun aSealedBatchDoesNotOpenWithoutThePhraseOrWithTheWrongOne() {
        val line = batch().encode(sealing())!!
        assertNull(SyncBatch.decode(line, null))
        assertNull(SyncBatch.decode(line, sealing("корвет уходит в морЕ")))
        assertNull(SyncBatch.decode(line, sealing("")))
    }

    @Test
    fun aBatchCannotBeMovedRenumberedOrUnsealedByEditingTheLine() {
        // The envelope's three fields are the sealed batch's associated data. Changing any of them
        // is what an attacker with write access to the folder would try: moving a batch into
        // another device's file, renumbering it to fill a gap, or claiming it was never sealed.
        val line = batch().encode(sealing())!!
        val json = JsonParser.parseString(line).asJsonObject

        val moved = json.deepCopy().apply { addProperty("d", desktop) }
        assertNull(SyncBatch.decode(moved.toString(), sealing()))

        val renumbered = json.deepCopy().apply { addProperty("n", 8L) }
        assertNull(SyncBatch.decode(renumbered.toString(), sealing()))

        val unsealed = json.deepCopy().apply { addProperty("e", SyncBatch.SEALING_NONE) }
        assertNull(SyncBatch.decode(unsealed.toString(), sealing()))

        val withAnotherNonce = json.deepCopy().apply { addProperty("iv", "AAAAAAAAAAAAAAAA") }
        assertNull(SyncBatch.decode(withAnotherNonce.toString(), sealing()))
    }

    @Test
    fun aChangedCiphertextDoesNotOpen() {
        val line = batch().encode(sealing())!!
        val json = JsonParser.parseString(line).asJsonObject
        val sealed = json.get("ct").asString
        // One character of the ciphertext changed, which is all a forgery takes to be noticed.
        val flipped = sealed.substring(0, 4) + (if (sealed[4] == 'A') 'B' else 'A') + sealed.substring(5)
        json.addProperty("ct", flipped)
        assertNull(SyncBatch.decode(json.toString(), sealing()))
    }

    @Test
    fun oneDevicesNoncesCannotRepeat() {
        // The one thing AES-GCM does not forgive. The nonce is the device's first four bytes and
        // then the batch counter, and a key belongs to one device — so two batches of one device
        // differ in the counter, and two devices cannot collide at all, whatever their counters.
        val seal = sealing()
        fun nonceOf(device: String, sequence: Long): String {
            val line = batch(device, sequence).encode(seal)!!
            return JsonParser.parseString(line).asJsonObject.get("iv").asString
        }
        val first = nonceOf(phone, 1)
        val second = nonceOf(phone, 2)
        val elsewhere = nonceOf(desktop, 1)
        assertNotEquals(first, second)
        assertNotEquals(first, elsewhere)
        // And it is derived rather than remembered, so the same batch written again is the same
        // nonce — which is why a batch number must never be spent twice.
        assertEquals(first, nonceOf(phone, 1))
    }

    @Test
    fun theKeyDerivationIsTheOneThePlatformsWouldHaveDone() {
        // Written out by hand because SecretKeyFactory only knows PBKDF2WithHmacSHA256 from API
        // 26 against this app's 23 — and a key derived one way here and another way there is two
        // keys. This checks the hand-written one against the JVM's own, which the desktop has.
        for (rounds in listOf(1, 2, 1_000)) {
            for (bytes in listOf(16, 32, 48)) {
                val mine = GcmSealing.pbkdf2(
                    password = phrase.toByteArray(Charsets.UTF_8),
                    salt = phone.toByteArray(Charsets.UTF_8),
                    rounds = rounds,
                    bytes = bytes
                )
                val platform = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(
                    PBEKeySpec(phrase.toCharArray(), phone.toByteArray(Charsets.UTF_8), rounds, bytes * 8)
                ).encoded
                assertEquals(
                    "$rounds rounds, $bytes bytes",
                    platform.joinToString("") { "%02x".format(it) },
                    mine.joinToString("") { "%02x".format(it) }
                )
            }
        }
    }

    @Test
    fun theKeyIsNotTheSameOnTwoDevices() {
        // Salted with the device's own id, which is a random 128-bit value. So nothing can be
        // precomputed against this app in general, and a key is used by exactly one writer.
        val mine = GcmSealing.pbkdf2(phrase.toByteArray(Charsets.UTF_8), phone.toByteArray(Charsets.UTF_8), 1_000)
        val theirs = GcmSealing.pbkdf2(phrase.toByteArray(Charsets.UTF_8), desktop.toByteArray(Charsets.UTF_8), 1_000)
        assertNotEquals(
            mine.joinToString("") { "%02x".format(it) },
            theirs.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun thePhraseIsNamedWithoutBeingTold() {
        val name = GcmSealing.fingerprintOf(phrase)
        assertEquals(16, name.length)
        assertEquals(name, GcmSealing.fingerprintOf(phrase))
        assertNotEquals(name, GcmSealing.fingerprintOf("корвет уходит в морЕ"))
        assertFalse(name.contains("корвет"))
        assertEquals(name, sealing().fingerprint)
    }

    @Test
    fun aSealingThisVersionDoesNotKnowIsSkipped() {
        // A line from a newer build. Dropping it loses that batch and nothing else, which is the
        // same thing that happens to a half-written line.
        val line = """{"d":"$phone","n":"3","e":"hsm-2031","iv":"AAAA","ct":"AAAA"}"""
        assertNull(SyncBatch.decode(line, sealing()))
        assertNull(SyncBatch.decode(line, null))
    }
}
