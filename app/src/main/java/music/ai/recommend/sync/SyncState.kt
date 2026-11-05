package music.ai.recommend.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * What a device has to remember between runs for sync to pick up where it left off.
 *
 * Four things, and each exists for a reason worth naming.
 *
 * [sequence] so that batch numbers keep rising across restarts, which is what makes a gap in the
 * other device's file mean a lost batch rather than a reboot.
 *
 * [exportedUpTo] so that this device's own listens go out once: history rows are numbered as they
 * are inserted, so one number says where the outbox got to, and a batch prepared but never written
 * is simply offered again on the next run.
 *
 * [progress] so that a day's file is read from where the last pass stopped instead of from the top.
 * Losing it costs a re-read and no records, since the merge turns away what is already held — so it
 * is a cache, and it is saved *after* the records it describes have been stored, never before.
 *
 * [unresolved] because a listen can arrive before the track it is about. The file may not have been
 * copied over yet, or not scanned yet, and throwing those away would quietly lose the history of
 * everything that arrives late — which on a device set up second is most of it.
 *
 * [aliases] so that a hash worked out the hard way, by tags or by the vectors, is worked out once.
 * The local track is kept as text because only the platform knows how to spell one — a MediaStore
 * id on the phone, a path on the desktop — and a state file written by either should at worst be
 * unreadable to the other rather than wrong.
 *
 * Written through a temporary file and renamed, because a half-written state file read back would
 * be worse than none: a sequence that went backwards would collide with batches already out there.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class SyncState private constructor(
    private val file: File,
    var sequence: Long,
    /** The highest history row id already written to the folder. */
    var exportedUpTo: Long,
    val progress: MutableMap<String, Int>,
    val unresolved: MutableList<Waiting>,
    val aliases: MutableMap<String, String>,
    /** Whether this folder's provider can append to a document; null until it has been tried. */
    var appendWorks: Boolean?
) {

    /** A listen whose track this library could not place yet. */
    data class Waiting(val deviceId: String, val record: PlayRecord)

    /** Keeps [unresolved] from growing without bound if a folder keeps offering tracks we lack. */
    fun rememberUnresolved(waiting: List<Waiting>) {
        unresolved.addAll(waiting)
        if (unresolved.size > MAX_UNRESOLVED) {
            // The oldest go first: a listen that has waited months for its file is the least
            // likely to ever find it.
            unresolved.subList(0, unresolved.size - MAX_UNRESOLVED).clear()
        }
    }

    fun save(): Boolean = try {
        val json = JsonObject()
        json.addProperty("v", 1)
        json.addProperty("sequence", sequence)
        json.addProperty("exportedUpTo", exportedUpTo)
        json.add("progress", JsonObject().apply {
            progress.forEach { (path, lines) -> addProperty(path, lines) }
        })
        json.add("unresolved", JsonArray().apply {
            unresolved.forEach { waiting ->
                add(waiting.record.toJson().apply { addProperty("dev", waiting.deviceId) })
            }
        })
        json.add("aliases", JsonObject().apply {
            aliases.forEach { (hash, track) -> addProperty(hash, track) }
        })
        appendWorks?.let { json.addProperty("appendWorks", it) }

        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".new")
        temporary.writeText(json.toString())
        if (temporary.renameTo(file)) true else { temporary.delete(); false }
    } catch (e: Exception) {
        false
    }

    companion object {

        /**
         * How many listens may wait for their tracks. Twenty thousand is years of someone else's
         * listening and a few megabytes of state; past that, the folder is describing a library
         * this device is never going to have.
         */
        const val MAX_UNRESOLVED = 20_000

        /**
         * Reads the state, or starts a fresh one. A file that will not parse is treated as absent:
         * everything in it is recoverable by reading the folder again, so there is no case for
         * trying to rescue half of it.
         */
        fun load(file: File): SyncState {
            val empty = SyncState(file, 0L, 0L, HashMap(), ArrayList(), HashMap(), null)
            val json = try {
                if (!file.isFile) return empty
                JsonParser.parseString(file.readText()) as? JsonObject ?: return empty
            } catch (e: Exception) {
                return empty
            }
            return try {
                SyncState(
                    file = file,
                    sequence = json.get("sequence")?.asLong ?: 0L,
                    exportedUpTo = json.get("exportedUpTo")?.asLong ?: 0L,
                    progress = HashMap<String, Int>().apply {
                        json.getAsJsonObject("progress")?.entrySet()?.forEach { (path, lines) ->
                            put(path, lines.asInt)
                        }
                    },
                    unresolved = ArrayList<Waiting>().apply {
                        json.getAsJsonArray("unresolved")?.forEach { element ->
                            val record = (element as? JsonObject) ?: return@forEach
                            val deviceId = record.get("dev")?.asString ?: return@forEach
                            PlayRecord.fromJson(record)?.let { add(Waiting(deviceId, it)) }
                        }
                    },
                    aliases = HashMap<String, String>().apply {
                        json.getAsJsonObject("aliases")?.entrySet()?.forEach { (hash, track) ->
                            // A file from before this was text holds numbers, which gson hands
                            // back as their own digits, so nothing has to be migrated.
                            put(hash, track.asString)
                        }
                    },
                    appendWorks = json.get("appendWorks")?.asBoolean
                )
            } catch (e: RuntimeException) {
                empty
            }
        }
    }
}
