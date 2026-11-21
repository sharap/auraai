package music.ai.recommend.db

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.io.File

/**
 * What happens to the prints already on a machine when the file grows a column.
 *
 * Worth a test of its own because of what those rows cost: a print is a seek and a quarter of a
 * megabyte per file, and the one library this was written against holds four and a half thousand of
 * them. A format change that silently dropped them would be invisible — the app would simply hash
 * everything again one quiet afternoon — and a format change that refused to read the old file
 * would be the same thing with extra steps.
 */
class SoundPrintFileTest {

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = File(System.getProperty("java.io.tmpdir"), "aimusic-prints-${System.nanoTime()}")
        baseDir.mkdirs()
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    /**
     * A prints.bin exactly as format 1 wrote it: path, stamp, print, and nothing else.
     *
     * The magic is written out here rather than read from the code on purpose. These are the bytes
     * on a machine that has been running the older build, and a test that took the number from
     * whatever the code currently says would agree with any change to it — including one that
     * stopped the old files from being recognised at all, which is the thing being tested.
     */
    private fun writeFormat1(rows: List<Triple<String, String, String>>) {
        DataOutputStream(File(baseDir, "prints.bin").outputStream().buffered()).use { out ->
            out.writeInt(0x41494D50)
            out.writeInt(1)
            out.writeInt(rows.size)
            for ((path, stamp, print) in rows) {
                out.writeUTF(path)
                out.writeUTF(stamp)
                out.writeUTF(print)
            }
        }
    }

    @Test
    fun printsWrittenByTheOlderFormatAreStillRead() = runBlocking {
        writeFormat1(
            listOf(
                Triple("/music/one.mp3", "1234:5678", "aa".repeat(32)),
                Triple("/music/two.flac", "4321:8765", "bb".repeat(32))
            )
        )
        val held = DesktopMusicDao(baseDir).soundPrints()

        assertEquals(setOf("/music/one.mp3", "/music/two.flac"), held.keys)
        assertEquals("aa".repeat(32), held.getValue("/music/one.mp3").print)
        assertEquals("1234:5678", held.getValue("/music/one.mp3").stamp)
        // The one thing the old format could not say.
        assertNull(held.getValue("/music/one.mp3").excerptStartMs)
    }

    @Test
    fun anExcerptStartJoinsAnOldRowWithoutUnseatingItsPrint() = runBlocking {
        writeFormat1(listOf(Triple("/music/one.mp3", "1234:5678", "aa".repeat(32))))

        DesktopMusicDao(baseDir).rememberSoundPrints(
            mapOf(
                "/music/one.mp3" to MusicDao.FilePrint(
                    stamp = "1234:5678",
                    print = "aa".repeat(32),
                    excerptStartMs = 94_000L
                )
            )
        )

        // Read back by a second dao, so this is the file speaking and not a cache.
        val reread = DesktopMusicDao(baseDir).soundPrint("/music/one.mp3")!!
        assertEquals("aa".repeat(32), reread.print)
        assertEquals(94_000L, reread.excerptStartMs)
    }

    @Test
    fun zeroIsAnAnswerAndNotAnAbsence() = runBlocking {
        // A recording shorter than the excerpt is analysed from its very beginning. If zero read
        // back as "nobody has worked this out", every such file would be walked again for ever.
        DesktopMusicDao(baseDir).rememberSoundPrints(
            mapOf("/music/short.wav" to MusicDao.FilePrint(stamp = "11:22", excerptStartMs = 0L))
        )

        val reread = DesktopMusicDao(baseDir).soundPrint("/music/short.wav")!!
        assertEquals(0L, reread.excerptStartMs)
        assertNull(reread.print)
    }

    @Test
    fun aHeldRowAnswersBeforeItIsWritten() = runBlocking {
        val dao = DesktopMusicDao(baseDir)
        // One row, far below the batch the file is rewritten for: the point of holding it is that
        // a conversation asking four thousand times does not rewrite the file four thousand times.
        dao.rememberSoundPrint("/music/one.mp3", MusicDao.FilePrint("11:22", excerptStartMs = 7L))

        assertEquals(7L, dao.soundPrint("/music/one.mp3")?.excerptStartMs)
        assertEquals(false, File(baseDir, "prints.bin").exists())
    }
}
