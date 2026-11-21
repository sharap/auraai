package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The announcement a device broadcasts, which is the one thing this app puts on the air
 * unprompted — so what it says to somebody without the phrase is worth pinning down.
 */
class BeaconTest {

    private val device = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val otherKey = ByteArray(32) { (it * 11 + 3).toByte() }
    private val now = 1_793_880_000_000L

    @Test
    fun `an announcement says who and where`() {
        val packed = Beacon.pack(device, 54_333, now, key)
        assertNotNull(packed)
        assertEquals(Beacon.Heard(device, 54_333, 0), Beacon.unpack(packed!!, key, now))
    }

    @Test
    fun `it says nothing to anybody else`() {
        val packed = Beacon.pack(device, 54_333, now, key)!!
        assertNull(Beacon.unpack(packed, otherKey, now))
        // And the id is nowhere in the bytes, which is the point of sealing something that is
        // broadcast to a whole network every few seconds.
        val onTheAir = packed.toString(Charsets.ISO_8859_1)
        assertTrue(device !in onTheAir)
        assertTrue("54333" !in onTheAir)
    }

    @Test
    fun `a stale announcement is not where a device is now`() {
        val packed = Beacon.pack(device, 54_333, now, key)!!
        assertNotNull(Beacon.unpack(packed, key, now + Beacon.FRESH_SECONDS * 1000 - 1000))
        assertNull(Beacon.unpack(packed, key, now + (Beacon.FRESH_SECONDS + 5) * 1000))
        // From the future as well: a clock that is wrong is not evidence of an address either.
        assertNull(Beacon.unpack(packed, key, now - (Beacon.FRESH_SECONDS + 5) * 1000))
    }

    @Test
    fun `a changed byte is not an announcement`() {
        val packed = Beacon.pack(device, 54_333, now, key)!!
        for (at in packed.indices) {
            val tampered = packed.copyOf()
            tampered[at] = (tampered[at] + 1).toByte()
            assertNull("прошёл с изменённым байтом $at", Beacon.unpack(tampered, key, now))
        }
    }

    @Test
    fun `rubbish on the port is ignored`() {
        for (datagram in listOf(
            ByteArray(0),
            ByteArray(12),
            ByteArray(13),
            "hello?".toByteArray(),
            ByteArray(Beacon.MAX_BEACON + 1)
        )) {
            assertNull(Beacon.unpack(datagram, key, now))
        }
    }

    @Test
    fun `two announcements never share a nonce`() {
        // Nonce reuse is the one thing AES-GCM does not forgive, and here there is no counter to
        // lean on: the nonce is random, so what is checked is that it really varies.
        val nonces = HashSet<String>()
        repeat(200) {
            val packed = Beacon.pack(device, 54_333, now, key)!!
            nonces.add(packed.copyOf(12).joinToString("") { b -> "%02x".format(b) })
        }
        assertEquals(200, nonces.size)
    }

    @Test
    fun `an announcement fits a datagram with room to spare`() {
        val packed = Beacon.pack(device, 65_535, now, key)!!
        assertTrue("маячок ${packed.size} байт", packed.size < 200)
    }

    @Test
    fun `an errand is carried and says nothing to anybody else`() {
        val packed = Beacon.pack(device, 54_333, now, key, wanted = 4_328)!!
        assertEquals(Beacon.Heard(device, 54_333, 4_328), Beacon.unpack(packed, key, now))
        // Sealed with the rest of it. How much music a device is missing is a fact about the
        // owner's library, and it is broadcast to the whole network every few seconds.
        assertTrue("4328" !in packed.toString(Charsets.ISO_8859_1))
        assertTrue(packed.size < 200)
    }

    @Test
    fun `wanting nothing is said by saying nothing`() {
        // The field is left out entirely, so a beacon from a device with nothing to ask for is
        // byte for byte the length it always was — and a build that never heard of errands reads
        // it unchanged.
        val quiet = Beacon.pack(device, 54_333, now, key)!!
        val alsoQuiet = Beacon.pack(device, 54_333, now, key, wanted = 0)!!
        assertEquals(quiet.size, alsoQuiet.size)
        assertEquals(0, Beacon.unpack(alsoQuiet, key, now)?.wanted)
        // And a nonsense errand is read as none rather than as a negative one.
        val odd = Beacon.pack(device, 54_333, now, key, wanted = -5)!!
        assertEquals(0, Beacon.unpack(odd, key, now)?.wanted)
    }

    @Test
    fun `an announcement asks for what the device is short of, freshly each time`() {
        // Asked per announcement and not kept, because the number's whole value is being current:
        // a device handed four thousand vectors must stop asking for them in the next breath.
        val answers = listOf(4_000, 0).iterator()
        val asked = java.util.concurrent.atomic.AtomicInteger(0)
        val beacon = Beacon(
            deviceId = device,
            port = 54_333,
            key = { key },
            wanted = { asked.incrementAndGet(); if (answers.hasNext()) answers.next() else 0 }
        )
        beacon.announce(now)
        beacon.announce(now)
        assertEquals(2, asked.get())
    }
}
