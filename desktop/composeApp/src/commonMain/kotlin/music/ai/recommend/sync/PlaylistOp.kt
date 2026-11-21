package music.ai.recommend.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * One change to the favourites or to a playlist, as it travels between devices.
 *
 * Listening history is a grow-only set, so merging it is just a union. Playlists are not: a track
 * added on one device and removed on the other has an answer that depends on which happened later,
 * and "later" cannot mean the wall clock — two devices' clocks drift, and a phone that was off for
 * a week will insist its own is right. So each change carries a Lamport counter [seq], which is one
 * more than the highest the writer has ever seen, and the device that wrote it. Replaying a merged
 * log in `(seq, deviceId)` order gives both devices the same answer, whatever order the files
 * arrived in.
 *
 * [at] is the wall clock, and it is for showing and for retention only. It must not decide
 * anything.
 *
 * A playlist is named by its name, which is what both apps already use — a blob keyed by name on
 * one side, `<name>.m3u` on the other. That makes [Kind.RENAME] an operation rather than a delete
 * and a create, and it makes two devices that independently make a playlist called the same thing
 * end up with one playlist rather than two with the same name, which for one person with two
 * devices is the answer they meant. The cost is that renaming the same playlist to two different
 * names on two devices keeps the later one and quietly drops the other, where a stable identifier
 * would have kept both apart.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
data class PlaylistOp(
    val kind: Kind,
    /** Lamport counter: one more than the highest this device has seen, local or arrived. */
    val seq: Long,
    val deviceId: String,
    /** Wall clock, for display and retention. Never for deciding. */
    val at: Long,
    /** The playlist this is about, or null for the favourites. */
    val playlist: String?,
    /** The track, for [Kind.ADD] and [Kind.REMOVE]. */
    val track: TrackKey? = null,
    /** The new name, for [Kind.RENAME]. */
    val newName: String? = null,
    /** The whole playlist in the order it is to be shown, for [Kind.ORDER]. */
    val tracks: List<TrackKey> = emptyList()
) {

    enum class Kind {
        /** The playlist exists, even if empty. Needed because an empty playlist is still a thing. */
        CREATE,

        /** The playlist is gone, with everything in it. */
        DELETE,

        /** The playlist is now called something else, keeping what is in it. */
        RENAME,

        /** The track belongs to this playlist, or to the favourites. */
        ADD,

        /** The track does not belong to it any more. */
        REMOVE,

        /**
         * The playlist is to be read in this order.
         *
         * It carries the whole list rather than "this track to the fourth place", because an
         * order is a property of the list and a position is not a thing two devices can agree
         * about: a move to the fourth place means something else if the other device has since
         * added two tracks, and nothing at all if it removed three. The whole list says what was
         * meant without depending on what the other one did.
         *
         * Replay applies it to what the playlist holds at that moment, and nothing else: tracks
         * it names and the playlist has take the order given, tracks the playlist has and the
         * order does not mention keep their relative order after them. So an addition that
         * crossed paths with a reordering is not lost, it is simply at the end — and both devices
         * put it in the same place.
         *
         * The favourites have no order of their own to set. Neither app stores one: on the phone
         * they are a set of ids shown in library order, on the desktop a set of paths. So this is
         * about a named playlist and [playlist] is required.
         */
        ORDER
    }

    /** Where two operations of the same [seq] are put in order, so both devices agree. */
    val order: Pair<Long, String> get() = seq to deviceId

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("k", kind.wire)
        addProperty("s", seq)
        addProperty("d", deviceId)
        addProperty("at", at)
        playlist?.let { addProperty("p", it) }
        newName?.let { addProperty("n", it) }
        track?.let { add("t", it.written()) }
        if (tracks.isNotEmpty()) {
            add("ts", JsonArray().apply { tracks.forEach { add(it.written()) } })
        }
    }

    companion object {

        private val Kind.wire: String
            get() = when (this) {
                Kind.CREATE -> "create"
                Kind.DELETE -> "delete"
                Kind.RENAME -> "rename"
                Kind.ADD -> "add"
                Kind.REMOVE -> "remove"
                Kind.ORDER -> "order"
            }

        private fun kindOf(wire: String): Kind? = when (wire) {
            "create" -> Kind.CREATE
            "delete" -> Kind.DELETE
            "rename" -> Kind.RENAME
            "add" -> Kind.ADD
            "remove" -> Kind.REMOVE
            "order" -> Kind.ORDER
            else -> null
        }

        /**
         * @return the operation, or null when the line does not hold one this version can act on —
         *   malformed, half-written, or of a kind a newer version invented. A reader that meets
         *   one drops it and goes on, as with a listen: the alternative is for one line to cost
         *   the rest of the log.
         *
         *   An operation of an unknown kind is dropped rather than kept and ignored, which is a
         *   real decision: it means an older build replaying a log written by a newer one may
         *   arrive at a state the newer one would not. The alternative — refusing the whole log —
         *   would leave the owner with no playlists at all, which is worse and harder to explain.
         */
        fun fromJson(json: JsonObject): PlaylistOp? {
            return try {
                val kind = kindOf(json.get("k")?.asString ?: return null) ?: return null
                val deviceId = json.get("d")?.asString ?: return null
                if (deviceId.isEmpty()) return null
                val seq = json.get("s")?.asLong ?: return null
                if (seq <= 0) return null
                val playlist = json.get("p")?.asString
                val newName = json.get("n")?.asString
                val track = json.getAsJsonObject("t")?.let { key -> TrackKey.read(key) ?: return null }
                // One unreadable track makes the whole order unusable rather than one place
                // wrong: a list with a hole in it is an order nobody asked for, and both devices
                // dropping the same line keeps them agreeing with each other.
                val tracks = json.getAsJsonArray("ts")
                    ?.map { TrackKey.read(it.asJsonObject) ?: return null }
                    .orEmpty()
                // What each kind cannot do without. A line missing its subject is not a change, and
                // guessing at it would mean replaying something nobody asked for.
                when (kind) {
                    Kind.ADD, Kind.REMOVE -> if (track == null) return null
                    Kind.CREATE, Kind.DELETE -> if (playlist.isNullOrEmpty()) return null
                    Kind.RENAME -> if (playlist.isNullOrEmpty() || newName.isNullOrEmpty()) return null
                    Kind.ORDER -> if (playlist.isNullOrEmpty() || tracks.isEmpty()) return null
                }
                PlaylistOp(
                    kind = kind,
                    seq = seq,
                    deviceId = deviceId,
                    at = json.get("at")?.asLong ?: 0L,
                    playlist = playlist,
                    track = track,
                    newName = newName,
                    tracks = tracks
                )
            } catch (e: RuntimeException) {
                // Gson throws on a number that is a string, a string that is an object, and so on.
                null
            }
        }
    }
}
