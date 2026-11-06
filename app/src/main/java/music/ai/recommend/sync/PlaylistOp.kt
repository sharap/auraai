package music.ai.recommend.sync

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
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
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
    val newName: String? = null
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
        REMOVE
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
        track?.let {
            add("t", JsonObject().apply {
                it.hash?.let { hash -> addProperty("h", hash) }
                addProperty("d", it.durationMs)
                if (it.artist.isNotEmpty()) addProperty("a", it.artist)
                if (it.title.isNotEmpty()) addProperty("n", it.title)
            })
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
            }

        private fun kindOf(wire: String): Kind? = when (wire) {
            "create" -> Kind.CREATE
            "delete" -> Kind.DELETE
            "rename" -> Kind.RENAME
            "add" -> Kind.ADD
            "remove" -> Kind.REMOVE
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
                val track = json.getAsJsonObject("t")?.let { key ->
                    val title = key.get("n")?.asString.orEmpty()
                    val hash = key.get("h")?.asString
                    if (title.isEmpty() && hash == null) return null
                    TrackKey(
                        hash = hash,
                        durationMs = key.get("d")?.asLong ?: 0L,
                        artist = key.get("a")?.asString.orEmpty(),
                        title = title
                    )
                }
                // What each kind cannot do without. A line missing its subject is not a change, and
                // guessing at it would mean replaying something nobody asked for.
                when (kind) {
                    Kind.ADD, Kind.REMOVE -> if (track == null) return null
                    Kind.CREATE, Kind.DELETE -> if (playlist.isNullOrEmpty()) return null
                    Kind.RENAME -> if (playlist.isNullOrEmpty() || newName.isNullOrEmpty()) return null
                }
                PlaylistOp(
                    kind = kind,
                    seq = seq,
                    deviceId = deviceId,
                    at = json.get("at")?.asLong ?: 0L,
                    playlist = playlist,
                    track = track,
                    newName = newName
                )
            } catch (e: RuntimeException) {
                // Gson throws on a number that is a string, a string that is an object, and so on.
                null
            }
        }
    }
}
