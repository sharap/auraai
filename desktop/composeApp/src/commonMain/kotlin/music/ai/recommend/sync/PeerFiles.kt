package music.ai.recommend.sync

import java.io.File
import java.io.RandomAccessFile

/**
 * The day files as bytes, which is what a socket moves and what a folder never had to expose.
 *
 * The readers above work in lines and must go on doing so — a line is a batch and the unit
 * everything is counted in. A transfer cannot: the two sides have to agree on a cursor without
 * trusting each other's idea of where a line ends, and for a file that is only ever appended to the
 * honest cursor is a byte count. So this interface exists beside [SyncSource] rather than inside
 * it, and the line readers go on reading whatever lands on disk.
 *
 * Two kinds of file, kept strictly apart, because the difference is a safety property and not a
 * convenience: this device's **own** directory, which only it may append to, and the **copies** it
 * keeps of other devices', which only they may. A peer that could write into our own directory
 * could forge our batches to a third device; a peer that could read any path it liked could ask
 * for the database. Hence [appendTo] taking the device whose directory it is, and every path being
 * checked against the shapes this program's own writers produce.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface PeerFiles {

    /** This device's own files, with how many bytes each holds. */
    fun own(): List<PeerProtocol.Held>

    /**
     * Bytes of one of this device's own files.
     *
     * @return the bytes, which may be fewer than [max] at the end of the file, or null when the
     *   path is not one of ours or cannot be read.
     */
    fun readOwn(path: String, at: Long, max: Int): ByteArray?

    /** What this device holds of [deviceId]'s files, with how many bytes of each. */
    fun heldOf(deviceId: String): List<PeerProtocol.Held>

    /**
     * Appends to a copy of [deviceId]'s file.
     *
     * Strictly appending, at exactly [at]: anything else is either two conversations racing or a
     * peer rewriting history, and both are better refused than guessed at.
     *
     * @return how many bytes the file holds afterwards, or null when nothing was written — a path
     *   that is not ours to hold, an offset that does not line up, [deviceId] being this device
     *   itself, or the disk refusing.
     */
    fun appendTo(deviceId: String, path: String, at: Long, bytes: ByteArray): Long?
}

/**
 * [PeerFiles] over a plain directory: `<root>/<deviceId>/history/2026-11-08.jsonl`.
 *
 * The same layout the shared folder has, on purpose. On the desktop it is a directory like any
 * other; on Android it is inside the app's own storage, which is the point — the storage access
 * framework is what made the folder slow and fragile there, and a device that talks over a socket
 * needs no shared folder at all.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class DirectoryPeerFiles(
    private val root: File,
    private val ownDeviceId: String
) : PeerFiles {

    override fun own(): List<PeerProtocol.Held> = listing(ownDeviceId)

    override fun heldOf(deviceId: String): List<PeerProtocol.Held> =
        if (deviceId == ownDeviceId) emptyList() else listing(deviceId)

    override fun readOwn(path: String, at: Long, max: Int): ByteArray? {
        if (!DayFile.isOurs(path) || at < 0 || max <= 0) return null
        val file = File(File(root, ownDeviceId), path)
        if (!file.isFile) return null
        return try {
            RandomAccessFile(file, "r").use { open ->
                if (at >= open.length()) return ByteArray(0)
                open.seek(at)
                val wanted = minOf(max.toLong(), open.length() - at).toInt()
                val buffer = ByteArray(wanted)
                open.readFully(buffer)
                buffer
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun appendTo(deviceId: String, path: String, at: Long, bytes: ByteArray): Long? {
        // A peer may add to its own directory and to no other, least of all to ours: our
        // directory is what another device would believe we wrote.
        if (deviceId.isEmpty() || deviceId == ownDeviceId) return null
        if (!DayFile.isOurs(path) || at < 0) return null
        val file = File(File(root, deviceId), path)
        return try {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { open ->
                // Strictly at the end. A gap would leave a hole full of zeros in the middle of
                // somebody's history; an overlap would rewrite what was already read.
                if (open.length() != at) return null
                open.seek(at)
                open.write(bytes)
                open.length()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun listing(deviceId: String): List<PeerProtocol.Held> {
        val directory = File(root, deviceId)
        if (!directory.isDirectory) return emptyList()
        val out = ArrayList<PeerProtocol.Held>()
        for (kind in directory.listFiles().orEmpty()) {
            if (!kind.isDirectory) continue
            for (entry in kind.listFiles().orEmpty()) {
                if (entry.isFile) {
                    add(out, "${kind.name}/${entry.name}", entry)
                    continue
                }
                // A day that became a directory, which is what a provider that cannot append
                // leaves behind. Read the same way here as the line readers read it.
                if (entry.isDirectory) {
                    for (part in entry.listFiles().orEmpty()) {
                        if (part.isFile) add(out, "${kind.name}/${entry.name}/${part.name}", part)
                    }
                }
            }
            if (out.size > PeerProtocol.MAX_FILES) break
        }
        return out.take(PeerProtocol.MAX_FILES)
    }

    private fun add(out: MutableList<PeerProtocol.Held>, path: String, file: File) {
        if (DayFile.isOurs(path)) out.add(PeerProtocol.Held(path, file.length()))
    }
}
