package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncIdentityTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun anIdIsSixteenBytesOfHex() {
        val id = SyncIdentity.newDeviceId()
        assertEquals(SyncIdentity.LENGTH, id.length)
        assertTrue(id, SyncIdentity.isValid(id))
    }

    @Test
    fun twoDevicesDoNotGetTheSameName() {
        val ids = (1..200).map { SyncIdentity.newDeviceId() }.toSet()
        assertEquals(200, ids.size)
    }

    @Test
    fun whatIsNotAnIdIsRejected() {
        assertFalse(SyncIdentity.isValid(""))
        assertFalse(SyncIdentity.isValid("ab"))
        assertFalse(SyncIdentity.isValid("A".repeat(32)))
        assertFalse(SyncIdentity.isValid("ab".repeat(16) + "cd"))
        assertFalse(SyncIdentity.isValid("gg" + "ab".repeat(15)))
        assertFalse(SyncIdentity.isValid("ab-" + "ab".repeat(14) + "a"))
    }

    @Test
    fun theIdIsCreatedOnceAndThenRemembered() {
        val file = File(folder.root, "sync/device-id")
        val first = SyncIdentity.loadOrCreate(file)!!
        assertTrue(file.isFile)
        assertEquals(first, SyncIdentity.loadOrCreate(file))
        assertEquals(first, SyncIdentity.loadOrCreate(file))
    }

    @Test
    fun aFileLeftHalfWrittenIsReplacedRatherThanBelieved() {
        // Reading back a truncated id would give this device a second name in the folder, and then
        // it would count its own records as somebody else's.
        val file = File(folder.root, "device-id")
        file.writeText("ab12")
        val id = SyncIdentity.loadOrCreate(file)!!
        assertTrue(SyncIdentity.isValid(id))
        assertEquals(id, file.readText().trim())
    }

    @Test
    fun surroundingWhitespaceIsForgiven() {
        val file = File(folder.root, "device-id")
        val written = SyncIdentity.newDeviceId()
        file.writeText("  $written\n")
        assertEquals(written, SyncIdentity.loadOrCreate(file))
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        val file = File(folder.root, "device-id")
        SyncIdentity.loadOrCreate(file)
        assertEquals(listOf("device-id"), folder.root.list()!!.sorted())
    }
}
