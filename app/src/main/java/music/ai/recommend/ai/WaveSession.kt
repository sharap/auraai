package music.ai.recommend.ai

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A running wave, written down so the stream survives the app being unloaded.
 *
 * Android reclaims the process of an app left in the background, which took the wave with it: the
 * queue came back from the saved playlist but the direction was gone, so what returned was an
 * ordinary queue of the last few tracks. Everything needed to carry on is here — where the stream
 * was heading, what it was steering clear of, and what it had already played.
 *
 * The library itself is not here: [MixTrack]s are rebuilt from the scan and the history, which is
 * cheap and keeps the file to a few kilobytes.
 */
data class WaveSession(
    /** [music.ai.recommend.WaveMode], by name, so the file does not depend on the enum's order. */
    val mode: String,
    /** Subtitle of the wave: the query, or the track it started from. */
    val description: String?,
    val state: WaveState,
    val savedAt: Long
)

/**
 * Reads and writes a [WaveSession] as a small binary record.
 *
 * Binary rather than JSON because three quarters of the content is 512-dimensional vectors: as
 * text they are twenty kilobytes of digits, as floats six. Any failure to read answers null — a
 * wave is a convenience, and a damaged file must never keep the app from starting.
 */
object WaveSessions {

    private const val VERSION = 1

    /** Guards against a corrupt length being read as an allocation request. */
    private const val MAX_VECTOR = 4096
    private const val MAX_IDS = 100_000

    fun write(out: OutputStream, session: WaveSession) {
        DataOutputStream(out.buffered()).use { sink ->
            sink.writeInt(VERSION)
            sink.writeLong(session.savedAt)
            sink.writeUTF(session.mode)
            sink.writeBoolean(session.description != null)
            sink.writeUTF(session.description.orEmpty())

            val state = session.state
            sink.writeInt(state.steps)
            sink.writeInt(state.rejections)
            sink.writeVector(state.direction)
            sink.writeVector(state.centre)
            sink.writeBoolean(state.avoid != null)
            state.avoid?.let { sink.writeVector(it) }
            sink.writeInt(state.played.size)
            for (id in state.played) sink.writeLong(id)
            sink.writeInt(state.recentGroups.size)
            for (group in state.recentGroups) sink.writeUTF(group)
        }
    }

    /** @return the session, or null when there is none to be had: truncated, or of an older shape. */
    fun read(input: InputStream): WaveSession? = try {
        DataInputStream(input.buffered()).use { source ->
            if (source.readInt() != VERSION) return null
            val savedAt = source.readLong()
            val mode = source.readUTF()
            val hasDescription = source.readBoolean()
            val description = source.readUTF().takeIf { hasDescription }

            val steps = source.readInt()
            val rejections = source.readInt()
            val direction = source.readVector()
            val centre = source.readVector()
            val avoid = if (source.readBoolean()) source.readVector() else null

            val playedCount = source.readCount(MAX_IDS)
            val played = HashSet<Long>(playedCount * 2)
            repeat(playedCount) { played += source.readLong() }
            val groupCount = source.readCount(MAX_IDS)
            val groups = ArrayList<String>(groupCount)
            repeat(groupCount) { groups += source.readUTF() }

            WaveSession(
                mode = mode,
                description = description,
                state = WaveState(
                    direction = direction,
                    centre = centre,
                    avoid = avoid,
                    played = played,
                    recentGroups = groups,
                    steps = steps,
                    rejections = rejections
                ),
                savedAt = savedAt
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun DataOutputStream.writeVector(v: FloatArray) {
        writeInt(v.size)
        for (x in v) writeFloat(x)
    }

    private fun DataInputStream.readVector(): FloatArray {
        val size = readInt()
        require(size in 1..MAX_VECTOR) { "implausible vector length $size" }
        return FloatArray(size) { readFloat() }
    }

    private fun DataInputStream.readCount(limit: Int): Int {
        val count = readInt()
        require(count in 0..limit) { "implausible count $count" }
        return count
    }
}
