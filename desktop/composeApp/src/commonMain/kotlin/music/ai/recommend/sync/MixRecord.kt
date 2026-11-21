package music.ai.recommend.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The playlist of the day as it travels.
 *
 * Unlike every other thing here, this one is not a fact to be merged but an opinion to be agreed
 * on. A listen happened; a playlist is the owner's; but "today's mix" is a list each device would
 * be perfectly capable of inventing for itself — and that is exactly the problem. Two devices
 * building their own would both be right and would show two different lists under one name, which
 * is not what somebody with a phone and a desktop means by *today's* mix. So one of the two lists
 * wins and both show it.
 *
 * Which one wins is [attempt], then [deviceId]. A device raises the attempt when it builds another
 * mix for a day it already has one for — which is what the owner asking for a different mix does
 * — so the newest deliberate act wins over an older one, and the device name settles the case
 * where both built their first mix of the day before meeting. The wall clock is not consulted,
 * for the same reason as everywhere else: a device that was off for a week insists its clock is
 * right.
 *
 * [day] is the **local** calendar date, not UTC, because it is the day the owner is living in and
 * the one the mix turns over on. A record for a day that is not this device's today is ignored
 * rather than applied, so two devices in different time zones simply stop sharing a mix near
 * midnight instead of overwriting each other's.
 *
 * An empty mix never travels. A device whose library is too small to choose from builds nothing,
 * and a record of nothing would otherwise be a way to wipe a good mix on the other device.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
data class MixRecord(
    /** The local calendar date the mix is for, `yyyy-MM-dd`. */
    val day: String,
    /** Raised each time a device builds another mix for the same day. */
    val attempt: Int,
    /** Which device built it. Breaks ties, and keeps a device from re-publishing what it adopted. */
    val deviceId: String,
    val tracks: List<TrackKey>
) {

    /** Which of two mixes for one day stands, so that both devices choose the same one. */
    val order: Pair<Int, String> get() = attempt to deviceId

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("m", day)
        addProperty("a", attempt)
        addProperty("d", deviceId)
        add("ts", JsonArray().apply { tracks.forEach { add(it.written()) } })
    }

    companion object {

        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

        /**
         * A limit on what one record may carry, so that a malformed or hostile line cannot make a
         * device hold a list it will never show. A day's mix is a few dozen tracks.
         */
        const val MAX_TRACKS = 2_000

        /**
         * @return the record, or null when the line does not hold one this version can act on. One
         *   unreadable track makes the whole record unusable rather than one place in it wrong: a
         *   mix with a hole in it is not the mix anybody built.
         */
        fun fromJson(json: JsonObject): MixRecord? {
            return try {
                val day = json.get("m")?.asString ?: return null
                if (!DAY.matches(day)) return null
                val deviceId = json.get("d")?.asString ?: return null
                if (deviceId.isEmpty()) return null
                val attempt = json.get("a")?.asInt ?: return null
                if (attempt < 0) return null
                val written = json.getAsJsonArray("ts") ?: return null
                if (written.size() == 0 || written.size() > MAX_TRACKS) return null
                val tracks = written.map { TrackKey.read(it.asJsonObject) ?: return null }
                MixRecord(day, attempt, deviceId, tracks)
            } catch (e: RuntimeException) {
                // Gson throws on a number that is a string, a string that is an object, and so on.
                null
            }
        }

        /** The mix for [day] that stands, out of everything on offer. */
        fun best(records: Iterable<MixRecord>, day: String): MixRecord? = records
            .filter { it.day == day }
            .maxWithOrNull(compareBy({ it.attempt }, { it.deviceId }))
    }
}
