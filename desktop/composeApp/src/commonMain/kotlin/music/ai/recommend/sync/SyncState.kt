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
 * [sequence], [vectorSequence], [playlistSequence] and [mixSequence] so that batch numbers keep
 * rising across restarts, which is what makes a gap in the other device's file mean a lost batch
 * rather than a reboot. Four counters because they are four streams of files: one counter for all
 * of them would leave gaps in each, and a reader of one would read the numbers the others had
 * spent as losses.
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
 * [vectorTerms] so that a change in what this device will believe a vector on makes it read the
 * folder's vectors again instead of standing by a refusal it would no longer make.
 *
 * [aliases] so that a hash worked out the hard way, by tags or by the vectors, is worked out once.
 * The local track is kept as text because only the platform knows how to spell one — a MediaStore
 * id on the phone, a path on the desktop — and a state file written by either should at worst be
 * unreadable to the other rather than wrong.
 *
 * Written through a temporary file and renamed, because a half-written state file read back would
 * be worse than none: a sequence that went backwards would collide with batches already out there.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class SyncState private constructor(
    private val file: File,
    var sequence: Long,
    /**
     * The batch counter for vectors, kept apart from [sequence] because they are two streams of
     * files. One counter for both would leave gaps in each — a reader of one would see the numbers
     * the other had spent and conclude that batches had been lost.
     */
    var vectorSequence: Long,
    /** The batch counter for playlist changes; a third stream of files, a third counter. */
    var playlistSequence: Long,
    /** The batch counter for the playlist of the day; a fourth stream, a fourth counter. */
    var mixSequence: Long,
    /**
     * The mix already published, as `day#attempt`, or null when none has been.
     *
     * A day and an attempt rather than a counter, because a mix is not a stream of changes but one
     * opinion per day that is replaced rather than added to. Keeping it stops the same mix being
     * written out on every pass, and lets the next attempt through the moment the owner asks for
     * another.
     */
    var mixPublished: String?,
    /**
     * The highest counter of this device's own playlist changes already written to the folder.
     *
     * One number does for the whole outbox because a device's own changes are numbered upwards:
     * a change prepared but never written is simply offered again on the next pass.
     */
    var playlistsPublishedUpTo: Long,
    /** Whether the playlists this device already had have been put into the log; see [PlaylistStore.current]. */
    var playlistsSeeded: Boolean,
    /** The highest history row id already written to the folder. */
    var exportedUpTo: Long,
    val progress: MutableMap<String, Int>,
    val unresolved: MutableList<Waiting>,
    val aliases: MutableMap<String, String>,
    /** Whether this folder's provider can append to a document; null until it has been tried. */
    var appendWorks: Boolean?,
    /**
     * A name for the phrase the folder was last read under, as [GcmSealing.fingerprintOf] gives it.
     *
     * Kept for the same reason as [vectorTerms]: a batch that could not be opened is marked read
     * and never offered again, so without noticing that the phrase has changed, the day the owner
     * fixes a typo on one device everything written before it would stay unreadable for ever. Null
     * means the folder was last read with no phrase at all, which is also a change worth noticing.
     */
    var sealingKey: String?,
    /**
     * Where the records this device has sent went, and where the ones it has read came from.
     *
     * Not a kind of transport but a *destination*: `direct` for the socket, which fills the app's
     * own directory, and `folder:<uri>` for a folder something else carries. Both matter, and for
     * the same reason as [sealingKey]: each destination has files of its own at the *same* relative
     * paths, so progress made against one says nothing about another. Without noticing the change,
     * a device would believe it had already read nine hundred lines of a file that has four, and
     * everything it had not seen would be stepped over for good. A change clears [progress], which
     * costs a re-read and no records, since the stores turn away what they already hold.
     *
     * Naming the folder and not merely the kind is what makes *choosing a new folder* a change too.
     * It is the same situation exactly — an empty destination whose files share the old one's
     * names — and it was invisible while this said only "folder".
     */
    var transport: String?,
    /**
     * Destinations whose copy of this device's **vectors** has already been filled.
     *
     * Vectors are the one thing not re-sent on every change of destination: eleven megabytes for a
     * library this size, and the device at the other end already holds what it was given. But a
     * destination that has *never* had them is a different matter — it can never hand them back,
     * and a folder that cannot restore a device is only half a folder. So each destination gets
     * them exactly once, and this is how that once is remembered.
     *
     * Kept per destination rather than per vector: the question is about the folder, not about the
     * row, and a set of two or three short strings answers it without a column in the table.
     */
    val vectorsFilled: MutableSet<String>,
    /**
     * The terms vectors were last read on, as [VectorTerms.fingerprint] spells them.
     *
     * Kept so that a change of them can undo the reading. A vector refused because it was made by
     * another export of the model is refused for good otherwise: the batch holding it is marked
     * read and never offered again, so the day the owner switches to the model the other device
     * uses, a library's worth of work that was sitting right there would stay unclaimed.
     */
    var vectorTerms: String?
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

    /**
     * Starts every outbox again, for a folder this device has not written into before.
     *
     * Switching transports gives this device an empty directory of its own while its outboxes still
     * say that everything has been sent — so without this the other device would receive only what
     * happens from now on, and a folder meant to be a cache a device can be restored from would
     * begin life missing its past.
     *
     * [progress] goes for the mirror-image reason: it counts lines read out of files in the folder
     * that is no longer in use, and the files in the new one carry the same names.
     *
     * The **vectors are not** reset, and that is a judgement rather than an oversight. They are
     * eleven megabytes of this library, the other device already holds a copy of every one it was
     * ever given, and re-writing them all on the flick of a switch would cost minutes of radio for
     * records nobody is missing. A device that loses its analysis gets it back by being handed it
     * again — which is what `publishedAt` being zeroed on an analysis change is for.
     */
    fun startOutboxAgain() {
        exportedUpTo = 0L
        playlistsPublishedUpTo = 0L
        playlistsSeeded = false
        mixPublished = null
        progress.clear()
    }

    /**
     * Whether this destination still needs this device's vectors — and records that it is about to
     * get them, so a destination is filled once however often the owner switches back and forth.
     */
    fun fillVectorsFor(destination: String): Boolean = vectorsFilled.add(destination)

    /**
     * Forgets how far the vectors files were read.
     *
     * For a destination that has none: over a socket vectors are asked for by name rather than
     * published, so those files are deleted, and a note of how many lines of a deleted file had
     * been read is a claim about nothing. Everything else in [progress] is left alone.
     *
     * @return true when there was anything to forget.
     */
    fun forgetVectorProgress(): Boolean =
        progress.keys.removeAll { it.contains("/${VectorWriter.DIRECTORY}/") }

    fun save(): Boolean = try {
        val json = JsonObject()
        json.addProperty("v", 1)
        json.addProperty("sequence", sequence)
        json.addProperty("vectorSequence", vectorSequence)
        json.addProperty("playlistSequence", playlistSequence)
        json.addProperty("mixSequence", mixSequence)
        json.addProperty("playlistsPublishedUpTo", playlistsPublishedUpTo)
        json.addProperty("playlistsSeeded", playlistsSeeded)
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
        mixPublished?.let { json.addProperty("mixPublished", it) }
        transport?.let { json.addProperty("transport", it) }
        json.add("vectorsFilled", JsonArray().apply { vectorsFilled.forEach(::add) })
        sealingKey?.let { json.addProperty("sealingKey", it) }
        appendWorks?.let { json.addProperty("appendWorks", it) }
        vectorTerms?.let { json.addProperty("vectorTerms", it) }

        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".new")
        temporary.writeText(json.toString())
        if (temporary.renameTo(file)) true else { temporary.delete(); false }
    } catch (e: Exception) {
        false
    }

    companion object {

        /**
         * Where a device's records go, named the way [transport] records it, or null for nowhere.
         *
         * One function because two platforms were each deciding this for themselves, and one of
         * them could answer `folder:` — a folder with no path — which is what a device looks like
         * *before its settings have been read*. Written down, that is a destination nothing can
         * ever match, so every real one afterwards looks like a change of mind: progress cleared,
         * outboxes restarted, and on a cached coordinator a whole run spent syncing into nowhere.
         * Nowhere has to be nothing, not an empty name for somewhere.
         *
         * @param folder where the folder transport writes — a tree uri on the phone, a path on the
         *   desktop. Null or empty means none has been chosen.
         */
        fun destinationOf(direct: Boolean, folder: String?): String? {
            if (direct) return DIRECT
            val where = folder?.takeIf { it.isNotEmpty() } ?: return null
            return "$FOLDER$where"
        }

        /** The socket transport: one destination, since the device's own directory is its own. */
        const val DIRECT = "direct"

        /** A folder destination is named after the folder, so that choosing another is a change. */
        const val FOLDER = "folder:"

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
            // Named, because a constructor of seventeen fields where several are numbers is one
            // where a positional call compiles and means something else.
            val empty = SyncState(
                file = file,
                sequence = 0L,
                vectorSequence = 0L,
                playlistSequence = 0L,
                mixSequence = 0L,
                mixPublished = null,
                playlistsPublishedUpTo = 0L,
                playlistsSeeded = false,
                exportedUpTo = 0L,
                progress = HashMap(),
                unresolved = ArrayList(),
                aliases = HashMap(),
                appendWorks = null,
                sealingKey = null,
                transport = null,
                vectorsFilled = HashSet(),
                vectorTerms = null
            )
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
                    vectorSequence = json.get("vectorSequence")?.asLong ?: 0L,
                    playlistSequence = json.get("playlistSequence")?.asLong ?: 0L,
                    mixSequence = json.get("mixSequence")?.asLong ?: 0L,
                    mixPublished = json.get("mixPublished")?.asString,
                    playlistsPublishedUpTo = json.get("playlistsPublishedUpTo")?.asLong ?: 0L,
                    playlistsSeeded = json.get("playlistsSeeded")?.asBoolean ?: false,
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
                    appendWorks = json.get("appendWorks")?.asBoolean,
                    sealingKey = json.get("sealingKey")?.asString,
                    transport = json.get("transport")?.asString,
                    vectorsFilled = HashSet<String>().apply {
                        json.getAsJsonArray("vectorsFilled")?.forEach { add(it.asString) }
                    },
                    vectorTerms = json.get("vectorTerms")?.asString
                )
            } catch (e: RuntimeException) {
                empty
            }
        }
    }
}
