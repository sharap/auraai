package music.ai.recommend.sync

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import music.ai.recommend.MusicViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * That the folder the owner actually picked can be written to and read back.
 *
 * Every other test about the folder uses a plain directory of its own, which proves the code and
 * says nothing about the one thing only a person can do: walk the system's directory chooser and
 * hand the app someone else's tree. What that returns is a grant, and a grant can be read-only, can
 * fail to survive the process, and can be for a provider that quietly refuses to append. So this
 * takes the choice as the app stored it and does the whole round trip over it.
 *
 * It skips itself when no folder has been picked, since there is then nothing to say.
 *
 * Writes one file into the picked folder and deletes it again. It goes beside the app's own data
 * and not under `history/`, so that a reader would never mistake it for somebody's listening.
 */
@RunWith(AndroidJUnit4::class)
class PickedFolderTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theFolderTheOwnerPickedCanBeWrittenToAndReadBack() {
        val stored = context.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)
            .getString(MusicViewModel.KEY_SYNC_FOLDER, null)
        assumeNotNull("папка синхронизации не выбрана — проверять нечего", stored)
        val uri = stored!!.toUri()

        // The grant has to outlive the process that asked for it, and it has to include writing.
        // Taking only read would leave a feature that looks set up and never sends anything.
        val kept = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        assertNotNull("разрешение на $uri не сохранено", kept)
        assertTrue("разрешение только на чтение: $uri", kept!!.isWritePermission)
        assertTrue("разрешение не на чтение: $uri", kept.isReadPermission)

        val deviceId = SyncIdentity.loadOrCreate(File(context.filesDir, "sync/device-id"))
        assertNotNull("нет идентификатора устройства", deviceId)
        val folder = SafSyncFolder.fromTree(context, uri, deviceId!!)
        assertTrue("в выбранную папку нельзя писать", folder.isUsable())

        val name = "test-round-trip.jsonl"
        val lines = listOf("""{"t":"один"}""", """{"t":"два"}""")
        try {
            assertTrue("первая строка не записалась", folder.append(name, lines.take(1)))
            // Whether this provider appends is settled by the first write, and it decides the shape
            // of every day file from here on. Either answer is correct; both are worth seeing.
            assertNotNull("дописывание не проверено", folder.appendWorks)
            // Reported, because it is the one fact about a folder that cannot be known in advance
            // and that decides the shape of every day file written into it from here on.
            android.util.Log.i("PickedFolderTest", "appendWorks=${folder.appendWorks} для $uri")
            assertTrue("вторая строка не записалась", folder.append(name, lines.drop(1)))

            val back = folder.lines(deviceId, name)
            assertNotNull("записанное не читается обратно", back)
            if (folder.appendWorks == true) {
                assertEquals("дописывание сказало «да», а строк не две", lines, back)
            } else {
                // Without append the second write replaces the file; the real format puts each
                // batch in its own file instead, which the day-directory tests already cover.
                assertEquals(lines.drop(1), back)
            }
        } finally {
            tidyUp(uri, deviceId, name)
        }
    }

    /** Leaves the folder as it was found, apart from the app's own empty directories. */
    private fun tidyUp(uri: android.net.Uri, deviceId: String, name: String) {
        runCatching {
            var current = DocumentFile.fromTreeUri(context, uri) ?: return
            for (segment in listOf("aura", "v1", deviceId)) {
                current = current.findFile(segment) ?: return
            }
            current.findFile(name)?.delete()
        }
    }
}
