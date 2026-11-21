package music.ai.recommend.sync

import java.io.File
import java.io.IOException

/**
 * The sync folder as an ordinary directory.
 *
 * On the desktop that is the folder the owner points at. On Android it is the app's own private
 * directory, which is what the socket transport uses: there is no shared folder to reach there at
 * all, and the storage access framework was the whole reason reaching one was slow and fragile.
 * The phone keeps `SafSyncFolder` beside this for a folder that Syncthing carries, and that one has
 * no twin here, because reaching a document tree is a question only Android has.
 *
 * Everything lives under `aura/v1/<device id>/`, one directory per device, and a device writes only
 * its own. There is no locking and no leader because there is nothing to contend for.
 *
 * Appending is the part that differs from the phone. There a provider may refuse the mode or accept
 * it and truncate, so the Android side probes it once and falls back to writing each batch as its
 * own file inside a directory named for the day. Here appending is simply what a file does, so a
 * failure to append is a real failure — a full disk, a folder gone with its drive — and the right
 * answer is to keep the batch and offer it again, not to change the shape of what is written.
 *
 * Reading still takes both shapes, because the device on the other side may be a phone whose
 * provider could not append.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class FileSyncFolder(
    /**
     * The folder's root, fetched rather than held: a directory can be unmounted or removed between
     * one pass and the next, and this way that is a `false` from one call instead of a handle that
     * has quietly meant nothing for an hour.
     */
    private val root: () -> File?,
    private val deviceId: String
) : SyncFolder {

    constructor(root: File, deviceId: String) : this({ root }, deviceId)

    /** Whether the folder can be reached and written to at all right now. */
    fun isUsable(): Boolean {
        val directory = root() ?: return false
        return directory.isDirectory && directory.canWrite()
    }

    override fun append(relativePath: String, lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        val file = resolve("$VERSION_PREFIX$deviceId/$relativePath", create = true) ?: return false
        return try {
            // One write of one buffer, each line ended before the next begins. A reader that
            // arrives mid-write still sees a whole number of lines or a half-written tail, and the
            // tail is what `HistoryReader` deliberately leaves unconsumed.
            file.appendBytes(lines.joinToString(separator = "\n", postfix = "\n").toByteArray())
            true
        } catch (e: IOException) {
            println("FileSyncFolder: could not write $relativePath: ${e.message}")
            false
        }
    }

    override fun devices(): List<String> =
        directory(VERSION_PREFIX)?.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()

    override fun files(deviceId: String, inside: String): List<String> {
        val root = directory("$VERSION_PREFIX$deviceId/$inside") ?: return emptyList()
        return root.listFiles().orEmpty().flatMap { child ->
            when {
                child.isDirectory -> child.listFiles().orEmpty().map { "$inside/${child.name}/${it.name}" }
                else -> listOf("$inside/${child.name}")
            }
        }
    }

    override fun lines(deviceId: String, relativePath: String): List<String>? = try {
        val file = resolve("$VERSION_PREFIX$deviceId/$relativePath", create = false)
        if (file == null || !file.isFile) null else file.readLines()
    } catch (e: Exception) {
        // Mid-copy, drive pulled out, permissions changed under us: all mean "not now" rather than
        // "never", and the reader treats a null as exactly that.
        println("FileSyncFolder: could not read $deviceId/$relativePath yet: ${e.message}")
        null
    }

    /**
     * The file at [path] inside the folder, and its parent directories made if asked for.
     *
     * Built a segment at a time rather than handed to `File(root, path)` so that nothing in a path
     * can leave the folder — `..` is refused outright. The paths come from this program, but a
     * folder someone else writes into is the one place where that stops being a good enough reason.
     */
    private fun resolve(path: String, create: Boolean): File? {
        var current = root() ?: return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null
        for (segment in segments.dropLast(1)) {
            current = File(current, segment)
            if (current.isDirectory) continue
            if (!create) return null
            if (!current.mkdirs() && !current.isDirectory) return null
        }
        return File(current, segments.last())
    }

    /** The directory at [path] inside the folder, or null when it is not there. */
    private fun directory(path: String): File? {
        var current = root() ?: return null
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            if (segment == "." || segment == "..") return null
            current = File(current, segment)
        }
        return current.takeIf { it.isDirectory }
    }

    private companion object {
        const val VERSION_PREFIX = "aura/v1/"
    }
}
