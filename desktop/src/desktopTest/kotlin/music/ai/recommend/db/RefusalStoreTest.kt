package music.ai.recommend.db

import kotlinx.coroutines.runBlocking
import java.io.DataOutputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file the analysis could not read, as this port stores it.
 *
 * Worth its own test because the same row has to behave three ways at once: invisible to everything
 * that computes a similarity, visible to the scan so it is not decoded again on every pass, and
 * absent from the outbox, since "my decoders could not read this" is not news another device can
 * use. And because there is a table on disk already holding such rows in the old shape — 512 zeros
 * — which has to come back as a refusal rather than as a vector pointing nowhere.
 */
class RefusalStoreTest {

    private lateinit var baseDir: File

    private val broken = "/music/trance3/Nephed - Nebulosity.mp3"
    private val whole = "/music/trance3/Nianaro - Ephemeral.mp3"

    @BeforeTest
    fun setUp() {
        baseDir = File.createTempFile("aimusic-refusals", "").let { file ->
            file.delete(); file.mkdirs(); file
        }
    }

    @AfterTest
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun vector(seed: Float) = List(EMBEDDING_DIMENSIONS) { seed + it }

    @Test
    fun `a refusal is kept apart from the vectors and survives a reload`() = runBlocking {
        val dao = DesktopMusicDao(baseDir)
        dao.insertEmbedding(EmbeddingEntity(whole, vector(0.5f)))
        dao.refuseEmbedding(broken, "2048:1700000000000")
        dao.forceSave()

        val reopened = DesktopMusicDao(baseDir)
        assertEquals(listOf(whole), reopened.getAllEmbeddings().map { it.path })
        assertEquals(mapOf(broken to "2048:1700000000000"), reopened.refusals())
        // Never offered to the other device: it may well read the file this one could not.
        assertEquals(listOf(whole), reopened.vectorsToPublish(100).map { it.path })
    }

    @Test
    fun `storing a vector clears the refusal`() = runBlocking {
        val dao = DesktopMusicDao(baseDir)
        dao.refuseEmbedding(broken, "2048:1700000000000")
        dao.insertEmbedding(EmbeddingEntity(broken, vector(1f)))
        dao.forceSave()

        val reopened = DesktopMusicDao(baseDir)
        assertTrue(reopened.refusals().isEmpty())
        assertEquals(listOf(broken), reopened.getAllEmbeddings().map { it.path })
    }

    @Test
    fun `a table written before refusals existed gives up its zero vectors`() = runBlocking {
        // Exactly what is on disk today: a row of 512 zeros standing for a file that would not
        // decode. It has to come back as a refusal with no stamp — which no present file can equal,
        // so the scan gives it one more chance and then records what the file now looks like.
        File(baseDir, "embeddings.bin").let { file ->
            DataOutputStream(file.outputStream().buffered()).use { out ->
                // "AIME", spelled out rather than borrowed from the store: a test about a file's
                // layout should say what the layout is.
                out.writeInt(0x41494D45)
                out.writeInt(2)
                out.writeInt(ANALYSIS_VERSION)
                out.writeInt(2)
                out.writeUTF(whole)
                out.writeInt(EMBEDDING_DIMENSIONS)
                repeat(EMBEDDING_DIMENSIONS) { out.writeFloat(0.25f) }
                out.writeLong(7L)
                out.writeUTF(broken)
                out.writeInt(EMBEDDING_DIMENSIONS)
                repeat(EMBEDDING_DIMENSIONS) { out.writeFloat(0f) }
                out.writeLong(9L)
            }
        }

        val dao = DesktopMusicDao(baseDir)
        assertEquals(listOf(whole), dao.getAllEmbeddings().map { it.path })
        assertEquals(mapOf(broken to ""), dao.refusals())

        // And it stays a refusal once the table has been written in the current format.
        dao.forceSave()
        assertEquals(mapOf(broken to ""), DesktopMusicDao(baseDir).refusals())
    }

    @Test
    fun `vectors with no track go only when told to`() = runBlocking {
        val dao = DesktopMusicDao(baseDir)
        dao.insertEmbedding(EmbeddingEntity(whole, vector(0.5f)))
        dao.refuseEmbedding(broken, "")
        dao.deleteEmbeddings(listOf(whole, broken))

        assertTrue(dao.getAllEmbeddings().isEmpty())
        assertTrue(dao.refusals().isEmpty())
        assertNull(DesktopMusicDao(baseDir).getAllEmbeddings().firstOrNull())
    }
}
