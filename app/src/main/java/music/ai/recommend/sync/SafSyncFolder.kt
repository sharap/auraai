package music.ai.recommend.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * The sync folder as Android can reach it: a tree the owner picked once, through the storage access
 * framework.
 *
 * Everything lives under `aura/v1/<device id>/`, one directory per device, and a device writes only
 * its own. There is no locking and no leader because there is nothing to contend for.
 *
 * Appending to an existing document is the awkward part. It is what the format wants — a file that
 * grows and is then finished, so that Syncthing carries only the new blocks — but `"wa"` is not a
 * mode every provider implements, and the one on a phone's own storage behaves differently from one
 * over a network share. So it is tried, and if it does not work the day is written as separate
 * whole files inside a directory named for it instead. The reader takes either shape, and the only
 * cost of the fallback is more files.
 */
class SafSyncFolder private constructor(
    context: Context,
    private val deviceId: String,
    /**
     * The folder's root, fetched rather than held: a tree can be revoked or unmounted between one
     * pass and the next, and a stale handle would fail in less obvious ways than a null.
     */
    private val root: () -> DocumentFile?
) : SyncSink, SyncSource {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** Set once the provider has shown whether it can append; null until then. */
    var appendWorks: Boolean? = null
        private set

    private val directories = HashMap<String, DocumentFile?>()

    /** Whether the folder can be reached at all right now. */
    fun isUsable(): Boolean = runCatching { root()?.canWrite() == true }.getOrDefault(false)

    override fun append(relativePath: String, lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        val payload = lines.joinToString(separator = "\n", postfix = "\n")
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')

        return try {
            // Settled before any real data is written, and settled once. Finding out by trying it
            // on the day's own file was a mistake worth remembering: the append succeeded, the
            // check said otherwise, and the batch was then also written as a separate file — one
            // listen in two places, saved only by the merge turning the second one away.
            if (appendWorks == null) appendWorks = probeAppend()

            if (appendWorks == true) {
                val directory = directory("$VERSION_PREFIX$deviceId/$parentPath", create = true) ?: return false
                val existing = directory.findFile(name)
                    ?: return write(directory, name, payload)
                return appendTo(existing, payload)
            }
            // The day becomes a directory and every batch a whole file inside it.
            val day = name.removeSuffix(".jsonl")
            val directory = directory("$VERSION_PREFIX$deviceId/$parentPath/$day", create = true) ?: return false
            write(directory, nextPartName(directory), payload)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write $relativePath", e)
            false
        }
    }

    override fun devices(): List<String> = try {
        directory(VERSION_PREFIX, create = false)
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { it.name }
            .orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "Could not list the sync folder", e)
        emptyList()
    }

    override fun files(deviceId: String): List<String> = try {
        val history = directory("$VERSION_PREFIX$deviceId/$HISTORY", create = false)
        history?.listFiles()?.flatMap { child ->
            val name = child.name ?: return@flatMap emptyList()
            when {
                child.isDirectory -> child.listFiles().mapNotNull { part ->
                    part.name?.let { "$HISTORY/$name/$it" }
                }
                else -> listOf("$HISTORY/$name")
            }
        }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "Could not list $deviceId", e)
        emptyList()
    }

    override fun lines(deviceId: String, relativePath: String): List<String>? = try {
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val directory = directory("$VERSION_PREFIX$deviceId/$parentPath", create = false)
        val file = directory?.findFile(name) ?: return null
        resolver.openInputStream(file.uri)?.use { input ->
            input.bufferedReader().readLines()
        }
    } catch (e: Exception) {
        // Mid-copy, permission withdrawn, device unplugged: all mean "not now" rather than "never".
        Log.i(TAG, "Could not read $deviceId/$relativePath yet: ${e.message}")
        null
    }

    /**
     * The directory at [path] relative to the tree, made if asked for and missing.
     *
     * Cached, because each step of a path is a query to the provider and the same handful of
     * directories is wanted over and over. The cache lives as long as this object, which is one
     * pass over the folder.
     */
    private fun directory(path: String, create: Boolean): DocumentFile? {
        val key = "$path/$create"
        directories[key]?.let { return it }
        var current = root() ?: return null
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            val existing = current.findFile(segment)
            current = when {
                existing != null && existing.isDirectory -> existing
                existing != null -> return null              // a file is in the way
                !create -> return null
                else -> current.createDirectory(segment) ?: return null
            }
        }
        directories[key] = current
        return current
    }

    private fun write(directory: DocumentFile, name: String, payload: String): Boolean {
        val file = directory.findFile(name) ?: directory.createFile(MIME, name) ?: return false
        resolver.openOutputStream(file.uri, "wt")?.use { it.write(payload.toByteArray()) } ?: return false
        return true
    }

    private fun appendTo(file: DocumentFile, payload: String): Boolean {
        resolver.openOutputStream(file.uri, "wa")?.use { it.write(payload.toByteArray()) } ?: return false
        return true
    }

    /**
     * Whether this provider really appends when asked to.
     *
     * Asked of a file of its own, written and read back and then deleted, because the two ways a
     * provider can disappoint are both silent: it may throw on the mode, or it may accept it and
     * truncate. Reading the bytes back is the only answer that cannot be misread — a length taken
     * from the wrong kind of document handle comes back as zero and looks exactly like failure.
     */
    private fun probeAppend(): Boolean {
        val directory = directory("$VERSION_PREFIX$deviceId", create = true) ?: return false
        val name = ".append-probe"
        runCatching { directory.findFile(name)?.delete() }
        val file = directory.createFile(MIME, name) ?: return false
        return try {
            resolver.openOutputStream(file.uri, "wt")?.use { it.write(FIRST) } ?: return false
            resolver.openOutputStream(file.uri, "wa")?.use { it.write(SECOND) } ?: return false
            val back = resolver.openInputStream(file.uri)?.use { it.readBytes() } ?: return false
            back.contentEquals(FIRST + SECOND)
        } catch (e: Exception) {
            Log.i(TAG, "This provider will not append; each batch becomes its own file")
            false
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun nextPartName(directory: DocumentFile): String {
        val used = directory.listFiles().mapNotNull { file ->
            file.name?.removeSuffix(".jsonl")?.toIntOrNull()
        }
        return "%04d.jsonl".format((used.maxOrNull() ?: 0) + 1)
    }

    companion object {

        /** The folder the owner picked through the storage access framework. */
        fun fromTree(context: Context, treeUri: Uri, deviceId: String) =
            SafSyncFolder(context, deviceId) {
                DocumentFile.fromTreeUri(context.applicationContext, treeUri)
            }

        /**
         * A plain directory. Not what the feature uses — the sync folder is somebody else's and
         * reached through a tree — but it is the same code over the same interface, which is what
         * makes the plumbing testable on a device at all.
         */
        fun fromDirectory(context: Context, directory: File, deviceId: String) =
            SafSyncFolder(context, deviceId) {
                directory.mkdirs()
                DocumentFile.fromFile(directory)
            }

        private const val TAG = "SafSyncFolder"
        private const val VERSION_PREFIX = "aura/v1/"
        private const val HISTORY = "history"
        private const val MIME = "application/x-ndjson"
        private val FIRST = byteArrayOf('a'.code.toByte())
        private val SECOND = byteArrayOf('b'.code.toByte())
    }
}
