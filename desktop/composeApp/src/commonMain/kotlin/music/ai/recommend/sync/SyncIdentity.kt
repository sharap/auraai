package music.ai.recommend.sync

import java.io.File
import java.security.SecureRandom

/**
 * Who this device is, as far as the sync folder is concerned.
 *
 * A random 128-bit value, written once and never changed. It needs no cryptographic structure of
 * its own: what proves a batch came from one of the owner's devices is that it was sealed with
 * their shared key, and this only has to name whose batch it is so that two devices' records can be
 * told apart and counted separately.
 *
 * It replaces what both apps key history by today — a MediaStore id on the phone, a file path on
 * the desktop — neither of which survives being described to anyone else.
 *
 * Kept in a file rather than in preferences or a database so that both platforms can share this
 * code: on Android it goes in the app's own private directory, on the desktop beside its other
 * state. Either way, losing it is survivable — the device becomes a new device, and its past
 * records keep arriving under the old name until they age out.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object SyncIdentity {

    /** Sixteen bytes as lowercase hex. */
    const val LENGTH = 32

    private const val BYTES = LENGTH / 2

    fun isValid(id: String): Boolean =
        id.length == LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }

    fun newDeviceId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        val builder = StringBuilder(LENGTH)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0xF])
        }
        return builder.toString()
    }

    /**
     * The id in [file], creating it on first call.
     *
     * Written through a temporary file and renamed, because the alternative — a half-written id
     * after the process died mid-write — would be read back as a different device, and then this
     * device would have two names in the folder and count its own records as somebody else's.
     *
     * @return the id, or null if it could neither be read nor written, in which case there is
     *   nothing useful to do but leave sync off.
     */
    fun loadOrCreate(file: File, random: SecureRandom = SecureRandom()): String? {
        runCatching {
            if (file.isFile) {
                val existing = file.readText().trim()
                if (isValid(existing)) return existing
            }
        }
        return runCatching {
            file.parentFile?.mkdirs()
            val created = newDeviceId(random)
            val temporary = File(file.parentFile, file.name + ".new")
            temporary.writeText(created)
            if (!temporary.renameTo(file)) {
                temporary.delete()
                return null
            }
            created
        }.getOrNull()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
