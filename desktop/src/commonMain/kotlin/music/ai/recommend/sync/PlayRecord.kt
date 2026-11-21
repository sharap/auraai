package music.ai.recommend.sync

import com.google.gson.JsonObject

/**
 * One finished listen, as it travels between devices.
 *
 * The same four numbers both apps already keep, with the track named by [TrackKey] instead of by a
 * MediaStore id or a file path, and with the device that heard it named in the batch around it.
 *
 * Written field by field rather than by reflecting over this class: the names here are a wire
 * format that another implementation has to read, so they belong in code where they can be seen,
 * and this way the release build's shrinker has nothing to be told about.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
data class PlayRecord(
    /** When the listen ended, epoch milliseconds. */
    val playedAt: Long,
    /** Furthest position reached, milliseconds; pauses and repeats do not add to it. */
    val playedMs: Long,
    /** The track's length as the playing device had it, or 0 if it never said. */
    val durationMs: Long,
    val track: TrackKey
) {

    /** How much of the track was reached, 0..1. The two apps judge a listen by this. */
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (playedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("at", playedAt)
        addProperty("ms", playedMs)
        addProperty("len", durationMs)
        add("k", track.written())
    }

    companion object {

        /**
         * @return the record, or null if the line does not hold one. A reader that meets something
         *   it cannot parse drops that record and goes on: the alternative is for one bad line,
         *   from a future version or a half-written file, to cost the whole history.
         */
        fun fromJson(json: JsonObject): PlayRecord? {
            return try {
                val key = json.getAsJsonObject("k") ?: return null
                val playedAt = json.get("at")?.asLong ?: return null
                val track = TrackKey.read(key) ?: return null
                PlayRecord(
                    playedAt = playedAt,
                    playedMs = json.get("ms")?.asLong ?: 0L,
                    durationMs = json.get("len")?.asLong ?: 0L,
                    track = track
                )
            } catch (e: RuntimeException) {
                // Gson throws on a number that is a string, a string that is an object, and so on.
                null
            }
        }
    }
}
