package music.ai.recommend.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The playlist of the day as a record, and which of two of them stands.
 *
 * Unlike the rest of this folder, a mix is not a fact to merge but an opinion to agree on: both
 * devices could build their own and both would be right, which is how one person ends up looking
 * at two different lists called today. So what is tested here is that the two always choose the
 * same one — and that the ways a record can be nonsense are all refused, because a record that is
 * taken in replaces a whole day's playlist.
 */
class MixRecordTest {

    private val phone = "aa".repeat(16)
    private val desktop = "bb".repeat(16)

    private fun track(title: String, duration: Long = 200_000) =
        TrackKey(hash = null, durationMs = duration, artist = "мельница", title = title)

    private fun mix(
        day: String = "2026-11-06",
        attempt: Int = 0,
        device: String = phone,
        titles: List<String> = listOf("один", "два")
    ) = MixRecord(day, attempt, device, titles.map { track(it) })

    @Test
    fun aRecordSurvivesTheRoundTrip() {
        val original = mix(attempt = 3, titles = listOf("ночная кобыла", "кисточка", "один"))
        val back = MixRecord.fromJson(JsonParser.parseString(original.toJson().toString()).asJsonObject)
        assertEquals(original, back)
        // Including the order, which is the whole of what a mix is.
        assertEquals(original.tracks, back!!.tracks)
    }

    @Test
    fun theHigherAttemptWins() {
        // Raising the attempt is what asking for a different mix does, so a later deliberate act
        // beats an earlier one whichever device did it.
        val records = listOf(mix(attempt = 1, device = desktop), mix(attempt = 2, device = phone))
        assertEquals(2, MixRecord.best(records, "2026-11-06")?.attempt)
        assertEquals(phone, MixRecord.best(records.reversed(), "2026-11-06")?.deviceId)
    }

    @Test
    fun atTheSameAttemptTheDeviceNameDecidesAndBothAgree() {
        // Two devices that each built their first mix of the day before meeting. Neither is more
        // right; what matters is that they choose the same one, in whatever order the files
        // arrived.
        val records = listOf(mix(device = phone), mix(device = desktop))
        assertEquals(desktop, MixRecord.best(records, "2026-11-06")?.deviceId)
        assertEquals(desktop, MixRecord.best(records.reversed(), "2026-11-06")?.deviceId)
    }

    @Test
    fun onlyTheDayAskedAboutIsConsidered() {
        // A record for another day is of no use to anybody: yesterday's mix is not today's, and a
        // device whose calendar has not reached tomorrow yet must not be handed tomorrow's.
        val records = listOf(
            mix(day = "2026-11-05", attempt = 9),
            mix(day = "2026-11-07", attempt = 9),
            mix(day = "2026-11-06", attempt = 0)
        )
        assertEquals("2026-11-06", MixRecord.best(records, "2026-11-06")?.day)
        assertEquals(0, MixRecord.best(records, "2026-11-06")?.attempt)
        assertNull(MixRecord.best(records, "2026-11-08"))
    }

    @Test
    fun aLineThisVersionCannotActOnIsDropped() {
        val good = """{"m":"2026-11-06","a":0,"d":"$phone","ts":[{"d":1,"n":"x"}]}"""
        assertEquals("2026-11-06", MixRecord.fromJson(JsonParser.parseString(good).asJsonObject)?.day)

        for (broken in listOf(
            """{"a":0,"d":"$phone","ts":[{"d":1,"n":"x"}]}""",                   // no day
            """{"m":"6 ноября","a":0,"d":"$phone","ts":[{"d":1,"n":"x"}]}""",     // not a date
            """{"m":"2026-11-06","d":"$phone","ts":[{"d":1,"n":"x"}]}""",         // no attempt
            """{"m":"2026-11-06","a":-1,"d":"$phone","ts":[{"d":1,"n":"x"}]}""",  // before the first
            """{"m":"2026-11-06","a":0,"ts":[{"d":1,"n":"x"}]}""",                // no device
            """{"m":"2026-11-06","a":0,"d":"","ts":[{"d":1,"n":"x"}]}""",         // nameless device
            """{"m":"2026-11-06","a":0,"d":"$phone"}""",                          // no tracks
            // An empty mix never travels: a device whose library is too small to choose from
            // builds nothing, and a record of nothing would be a way to wipe a good mix.
            """{"m":"2026-11-06","a":0,"d":"$phone","ts":[]}""",
            """{"m":"2026-11-06","a":0,"d":"$phone","ts":[{"d":1}]}""",           // a nameless track
            """{"m":"2026-11-06","a":0,"d":"$phone","ts":{"d":1,"n":"x"}}""",     // not a list
            """{"m":"2026-11-06","a":"скоро","d":"$phone","ts":[{"d":1,"n":"x"}]}"""  // a word
        )) {
            assertNull(broken, MixRecord.fromJson(JsonParser.parseString(broken).asJsonObject))
        }
    }

    @Test
    fun aRecordLongerThanADayCouldHoldIsRefused() {
        // A guard against a malformed or hostile line making a device hold a list it will never
        // show. A day's mix is a few dozen tracks.
        val huge = MixRecord(
            day = "2026-11-06",
            attempt = 0,
            deviceId = phone,
            tracks = (1..MixRecord.MAX_TRACKS + 1).map { track("трек $it") }
        )
        assertNull(MixRecord.fromJson(huge.toJson()))
        val atTheLimit = MixRecord(
            day = "2026-11-06",
            attempt = 0,
            deviceId = phone,
            tracks = (1..MixRecord.MAX_TRACKS).map { track("трек $it") }
        )
        assertEquals(MixRecord.MAX_TRACKS, MixRecord.fromJson(atTheLimit.toJson())?.tracks?.size)
    }
}
