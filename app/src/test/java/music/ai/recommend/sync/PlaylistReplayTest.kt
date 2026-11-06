package music.ai.recommend.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Playlists and favourites as a log of changes, and the state that falls out of replaying it.
 *
 * What is being tested is convergence: both devices hold the same changes once their files have
 * met, so whatever order those files arrived in, replaying gives the same answer. Everything here
 * is therefore some version of "shuffle the log and get the same thing".
 *
 * The wall clock is deliberately wrong in several of these — a phone that was off for a week will
 * insist its clock is right — and it must change nothing.
 */
class PlaylistReplayTest {

    private val phone = "aa".repeat(16)
    private val desktop = "bb".repeat(16)
    private val now = 1_793_880_000_000L

    private fun track(title: String, duration: Long = 200_000) =
        TrackKey(hash = null, durationMs = duration, artist = "мельница", title = title)

    private fun op(
        kind: PlaylistOp.Kind,
        seq: Long,
        device: String = phone,
        playlist: String? = null,
        title: String? = null,
        newName: String? = null,
        at: Long = now,
        duration: Long = 200_000
    ) = PlaylistOp(kind, seq, device, at, playlist, title?.let { track(it, duration) }, newName)

    private fun order(
        seq: Long,
        playlist: String,
        vararg titles: String,
        device: String = phone
    ) = PlaylistOp(
        kind = PlaylistOp.Kind.ORDER,
        seq = seq,
        deviceId = device,
        at = now,
        playlist = playlist,
        tracks = titles.map { track(it) }
    )

    private fun replay(vararg ops: PlaylistOp) = PlaylistReplay.replay(ops.toList())

    /** The same changes in several orders, which must all come out the same way. */
    private fun shuffles(ops: List<PlaylistOp>) = listOf(
        ops, ops.reversed(),
        ops.shuffled(kotlin.random.Random(1)),
        ops.shuffled(kotlin.random.Random(2)),
        ops.shuffled(kotlin.random.Random(3))
    )

    @Test
    fun aHeartTappedOnOneDeviceShowsOnTheOther() {
        val state = replay(op(PlaylistOp.Kind.ADD, 1, title = "ночная кобыла"))
        assertEquals(listOf(track("ночная кобыла")), state.favourites)
        assertEquals(emptyMap<String, List<TrackKey>>(), state.playlists)
    }

    @Test
    fun theOrderOfTheFilesDoesNotMatter() {
        // The same four changes in six orders. A folder hands files over in whatever order
        // Syncthing finished them, and that must not be able to change the answer.
        val ops = listOf(
            op(PlaylistOp.Kind.ADD, 1, title = "один", playlist = "вечер"),
            op(PlaylistOp.Kind.ADD, 2, device = desktop, title = "два", playlist = "вечер"),
            op(PlaylistOp.Kind.REMOVE, 3, title = "один", playlist = "вечер"),
            op(PlaylistOp.Kind.ADD, 4, device = desktop, title = "три", playlist = "вечер")
        )
        val expected = mapOf("вечер" to listOf(track("два"), track("три")))
        for (shuffled in listOf(
            ops, ops.reversed(), ops.shuffled(kotlin.random.Random(1)),
            ops.shuffled(kotlin.random.Random(2)), ops.shuffled(kotlin.random.Random(3))
        )) {
            assertEquals(shuffled.map { it.seq }.toString(), expected, PlaylistReplay.replay(shuffled).playlists)
        }
    }

    @Test
    fun theCounterDecidesAndNotTheClock() {
        // The phone's clock is a week fast and it is the earlier change. The counter says so, and
        // the counter is what counts: the removal answers the addition and must come after it.
        val added = op(PlaylistOp.Kind.ADD, 1, title = "кисточка", at = now + 7 * 24 * 3600_000L)
        val removed = op(PlaylistOp.Kind.REMOVE, 2, device = desktop, title = "кисточка", at = now)
        assertEquals(emptyList<TrackKey>(), replay(added, removed).favourites)
        assertEquals(emptyList<TrackKey>(), replay(removed, added).favourites)
    }

    @Test
    fun twoDevicesAtTheSameCounterAreBrokenByTheirName() {
        // Both acted without having seen the other, so both chose the same number. Something has
        // to decide, it has to be the same something on both sides, and the only thing to hand is
        // the device's own name.
        val byPhone = op(PlaylistOp.Kind.ADD, 5, device = phone, title = "один", playlist = "p")
        val byDesktop = op(PlaylistOp.Kind.ADD, 5, device = desktop, title = "два", playlist = "p")
        assertEquals(
            listOf(track("один"), track("два")),
            replay(byDesktop, byPhone).playlists.getValue("p")
        )
        assertEquals(
            listOf(track("один"), track("два")),
            replay(byPhone, byDesktop).playlists.getValue("p")
        )
    }

    @Test
    fun anEmptyPlaylistIsStillAPlaylist() {
        val state = replay(op(PlaylistOp.Kind.CREATE, 1, playlist = "пока пусто"))
        assertEquals(mapOf("пока пусто" to emptyList<TrackKey>()), state.playlists)
    }

    @Test
    fun aDeletedPlaylistTakesItsTracksWithIt() {
        val state = replay(
            op(PlaylistOp.Kind.ADD, 1, title = "один", playlist = "вечер"),
            op(PlaylistOp.Kind.DELETE, 2, playlist = "вечер")
        )
        assertEquals(emptyMap<String, List<TrackKey>>(), state.playlists)

        // And a track added afterwards brings the playlist back, which is what adding to a
        // playlist that is not there has to mean: the alternative is losing the change.
        val after = replay(
            op(PlaylistOp.Kind.ADD, 1, title = "один", playlist = "вечер"),
            op(PlaylistOp.Kind.DELETE, 2, playlist = "вечер"),
            op(PlaylistOp.Kind.ADD, 3, title = "два", playlist = "вечер")
        )
        assertEquals(mapOf("вечер" to listOf(track("два"))), after.playlists)
    }

    @Test
    fun aRenameKeepsWhatWasInIt() {
        val state = replay(
            op(PlaylistOp.Kind.ADD, 1, title = "один", playlist = "вечер"),
            op(PlaylistOp.Kind.RENAME, 2, playlist = "вечер", newName = "ночь"),
            op(PlaylistOp.Kind.ADD, 3, title = "два", playlist = "ночь")
        )
        assertEquals(mapOf("ночь" to listOf(track("один"), track("два"))), state.playlists)
    }

    @Test
    fun twoRenamesOfTheSamePlaylistStillAgree() {
        // The expensive case for naming a playlist by its name. Both devices rename it, to
        // different things, without having seen each other. The later change wins and the earlier
        // one finds nothing left to rename — and both devices reach that same conclusion, which is
        // what matters. With a stable identifier the two would have stayed apart instead.
        val toNight = op(PlaylistOp.Kind.RENAME, 5, device = phone, playlist = "вечер", newName = "ночь")
        val toMorning = op(PlaylistOp.Kind.RENAME, 5, device = desktop, playlist = "вечер", newName = "утро")
        val made = op(PlaylistOp.Kind.ADD, 1, title = "один", playlist = "вечер")
        assertEquals(
            replay(made, toNight, toMorning).playlists,
            replay(made, toMorning, toNight).playlists
        )
        // The phone's name sorts first at the same counter, so the desktop's rename is the one
        // that finds nothing to do.
        assertEquals(mapOf("ночь" to listOf(track("один"))), replay(made, toNight, toMorning).playlists)
    }

    @Test
    fun aRenameIntoAnExistingPlaylistMergesRatherThanOverwrites() {
        val state = replay(
            op(PlaylistOp.Kind.ADD, 1, title = "один", playlist = "вечер"),
            op(PlaylistOp.Kind.ADD, 2, title = "два", playlist = "ночь"),
            op(PlaylistOp.Kind.RENAME, 3, playlist = "вечер", newName = "ночь")
        )
        assertEquals(mapOf("ночь" to listOf(track("два"), track("один"))), state.playlists)
    }

    @Test
    fun addingWhatIsAlreadyThereChangesNothing() {
        // Two devices can add the same track before they have met, and neither was wrong.
        val state = replay(
            op(PlaylistOp.Kind.ADD, 1, device = phone, title = "один", playlist = "p"),
            op(PlaylistOp.Kind.ADD, 2, device = desktop, title = "один", playlist = "p")
        )
        assertEquals(listOf(track("один")), state.playlists.getValue("p"))
    }

    @Test
    fun theCounterIsOneMoreThanAnythingSeen() {
        assertEquals(1L, PlaylistReplay.nextSeq(emptyList()))
        assertEquals(
            8L,
            PlaylistReplay.nextSeq(
                listOf(
                    op(PlaylistOp.Kind.ADD, 7, device = desktop, title = "один"),
                    op(PlaylistOp.Kind.ADD, 3, title = "два")
                )
            )
        )
    }

    @Test
    fun anOperationSurvivesTheRoundTrip() {
        val original = PlaylistOp(
            kind = PlaylistOp.Kind.ADD,
            seq = 42,
            deviceId = phone,
            at = now,
            playlist = "вечер",
            track = TrackKey("ab".repeat(32), 200_000, "мельница", "ночная кобыла")
        )
        val back = PlaylistOp.fromJson(JsonParser.parseString(original.toJson().toString()).asJsonObject)
        assertEquals(original, back)

        val renamed = PlaylistOp(PlaylistOp.Kind.RENAME, 7, desktop, now, "вечер", newName = "ночь")
        assertEquals(renamed, PlaylistOp.fromJson(renamed.toJson()))

        val favourite = PlaylistOp(PlaylistOp.Kind.REMOVE, 9, phone, now, null, track("кисточка"))
        assertEquals(favourite, PlaylistOp.fromJson(favourite.toJson()))
        // The favourites are the absence of a playlist, so the field is left out rather than null.
        assertEquals(false, favourite.toJson().toString().contains("\"p\""))
    }

    @Test
    fun aLineThisVersionCannotActOnIsDropped() {
        for (broken in listOf(
            """{"s":1,"d":"$phone","at":1,"t":{"d":1,"n":"x"}}""",                      // no kind
            """{"k":"add","d":"$phone","at":1,"t":{"d":1,"n":"x"}}""",                   // no counter
            """{"k":"add","s":0,"d":"$phone","at":1,"t":{"d":1,"n":"x"}}""",             // counter zero
            """{"k":"add","s":1,"at":1,"t":{"d":1,"n":"x"}}""",                          // no device
            """{"k":"add","s":1,"d":"","at":1,"t":{"d":1,"n":"x"}}""",                   // nameless device
            """{"k":"add","s":1,"d":"$phone","at":1}""",                                 // no track
            """{"k":"add","s":1,"d":"$phone","at":1,"t":{"d":1}}""",                     // nameless track
            """{"k":"remove","s":1,"d":"$phone","at":1}""",                              // no track
            """{"k":"create","s":1,"d":"$phone","at":1}""",                              // no playlist
            """{"k":"delete","s":1,"d":"$phone","at":1,"p":""}""",                       // nameless
            """{"k":"rename","s":1,"d":"$phone","at":1,"p":"вечер"}""",                  // no new name
            """{"k":"pin","s":1,"d":"$phone","at":1,"p":"вечер"}""",                     // a newer kind
            """{"k":"order","s":1,"d":"$phone","at":1,"p":"вечер"}""",                   // an order of nothing
            """{"k":"order","s":1,"d":"$phone","at":1,"ts":[{"d":1,"n":"x"}]}""",         // of no playlist
            """{"k":"order","s":1,"d":"$phone","at":1,"p":"вечер","ts":[{"d":1}]}""",     // a nameless track
            """{"k":"order","s":1,"d":"$phone","at":1,"p":"вечер","ts":{"d":1,"n":"x"}}""", // not a list
            """{"k":"add","s":"скоро","d":"$phone","at":1,"t":{"d":1,"n":"x"}}"""        // a word
        )) {
            assertNull(broken, PlaylistOp.fromJson(JsonParser.parseString(broken).asJsonObject))
        }
    }

    // ----------------------------------------------------------------- the order of a playlist

    @Test
    fun theOrderOneDeviceDraggedIsTheOrderOnBoth() {
        val ops = listOf(
            op(PlaylistOp.Kind.ADD, 1, playlist = "вечер", title = "один"),
            op(PlaylistOp.Kind.ADD, 2, playlist = "вечер", title = "два"),
            op(PlaylistOp.Kind.ADD, 3, playlist = "вечер", title = "три"),
            order(4, "вечер", "три", "один", "два")
        )
        val expected = mapOf("вечер" to listOf(track("три"), track("один"), track("два")))
        for (shuffled in shuffles(ops)) {
            assertEquals(expected, PlaylistReplay.replay(shuffled).playlists)
        }
    }

    @Test
    fun aTrackThatArrivedWhileTheListWasBeingDraggedIsAtTheEndOnBothDevices() {
        // The case this design exists for. One device arranges three tracks; the other adds a
        // fourth at about the same time, so each knows something the other does not. Whichever
        // change carries the higher counter, both must end up with the same four in the same
        // places — and the one nobody arranged goes last rather than nowhere.
        val built = listOf(
            op(PlaylistOp.Kind.ADD, 1, playlist = "вечер", title = "один"),
            op(PlaylistOp.Kind.ADD, 2, playlist = "вечер", title = "два"),
            op(PlaylistOp.Kind.ADD, 3, playlist = "вечер", title = "три")
        )
        val expected = mapOf(
            "вечер" to listOf(track("три"), track("один"), track("два"), track("четыре"))
        )

        // The arranging came first and the addition answered it.
        val arrangedFirst = built +
            order(4, "вечер", "три", "один", "два") +
            op(PlaylistOp.Kind.ADD, 5, device = desktop, playlist = "вечер", title = "четыре")
        for (shuffled in shuffles(arrangedFirst)) {
            assertEquals(expected, PlaylistReplay.replay(shuffled).playlists)
        }

        // And the other way about: the addition is older, but the device doing the arranging had
        // not seen it, so its order does not mention the fourth track at all.
        val addedFirst = built +
            op(PlaylistOp.Kind.ADD, 4, device = desktop, playlist = "вечер", title = "четыре") +
            order(5, "вечер", "три", "один", "два")
        for (shuffled in shuffles(addedFirst)) {
            assertEquals(expected, PlaylistReplay.replay(shuffled).playlists)
        }
    }

    @Test
    fun twoDevicesArrangingTheSameListAtOnceStillAgree() {
        val built = listOf(
            op(PlaylistOp.Kind.ADD, 1, playlist = "вечер", title = "один"),
            op(PlaylistOp.Kind.ADD, 2, playlist = "вечер", title = "два"),
            op(PlaylistOp.Kind.ADD, 3, playlist = "вечер", title = "три")
        )
        // Both at the same counter, so the device name breaks it — and the desktop's name sorts
        // after the phone's. Not a kind answer to the owner, who arranged two lists and keeps
        // one, but it is the same answer on both devices, which is the part that matters.
        val ops = built +
            order(4, "вечер", "один", "два", "три", device = phone) +
            order(4, "вечер", "три", "два", "один", device = desktop)
        val expected = mapOf("вечер" to listOf(track("три"), track("два"), track("один")))
        for (shuffled in shuffles(ops)) {
            assertEquals(expected, PlaylistReplay.replay(shuffled).playlists)
        }
    }

    @Test
    fun anOrderForAPlaylistThatIsGoneDoesNotBringItBack() {
        val state = replay(
            op(PlaylistOp.Kind.ADD, 1, playlist = "вечер", title = "один"),
            op(PlaylistOp.Kind.DELETE, 2, playlist = "вечер"),
            order(3, "вечер", "один")
        )
        assertEquals(emptyMap<String, List<TrackKey>>(), state.playlists)
    }

    @Test
    fun anOrderNeitherAddsNorRemovesATrack() {
        // It says how to read the playlist, not what is in it. A track it names that the playlist
        // does not hold — removed meanwhile, or never there — is not put in by being mentioned.
        val state = replay(
            op(PlaylistOp.Kind.ADD, 1, playlist = "вечер", title = "один"),
            op(PlaylistOp.Kind.ADD, 2, playlist = "вечер", title = "два"),
            op(PlaylistOp.Kind.REMOVE, 3, device = desktop, playlist = "вечер", title = "один"),
            order(4, "вечер", "один", "три", "два")
        )
        assertEquals(mapOf("вечер" to listOf(track("два"))), state.playlists)
    }

    @Test
    fun arrangingOnePlaylistLeavesTheOthersAndTheirListingAlone() {
        val state = replay(
            op(PlaylistOp.Kind.ADD, 1, playlist = "вечер", title = "один"),
            op(PlaylistOp.Kind.ADD, 2, playlist = "вечер", title = "два"),
            op(PlaylistOp.Kind.ADD, 3, playlist = "утро", title = "три"),
            op(PlaylistOp.Kind.ADD, 4, title = "кисточка"),
            order(5, "вечер", "два", "один")
        )
        // The playlists are still listed as they first appeared, and the favourites are untouched.
        assertEquals(listOf("вечер", "утро"), state.playlists.keys.toList())
        assertEquals(listOf(track("два"), track("один")), state.playlists["вечер"])
        assertEquals(listOf(track("три")), state.playlists["утро"])
        assertEquals(listOf(track("кисточка")), state.favourites)
    }

    @Test
    fun anOrderSurvivesTheRoundTrip() {
        val original = PlaylistOp(
            kind = PlaylistOp.Kind.ORDER,
            seq = 11,
            deviceId = desktop,
            at = now,
            playlist = "вечер",
            tracks = listOf(
                TrackKey("ab".repeat(32), 200_000, "мельница", "ночная кобыла"),
                track("кисточка"),
                track("один")
            )
        )
        val back = PlaylistOp.fromJson(JsonParser.parseString(original.toJson().toString()).asJsonObject)
        assertEquals(original, back)
        // Including the order itself, which is the whole of what it says.
        assertEquals(original.tracks, back!!.tracks)
    }


    // ------------------------------------------------- one track, two devices' way of saying it

    @Test
    fun whatTheTwoPlatformsCallOneTrackIsOneTrack() {
        // The numbers are out of the phone's own log. The desktop's tag reader gives whole seconds
        // and the phone's MediaStore gives milliseconds, and nothing else about the track differs.
        // Compared by equality — which is what this did at first — one device's description of a
        // track is a different recording from the other's: a removal of a track the other device
        // had added did nothing, and neither did a reordering of a playlist that came from it.
        val removed = replay(
            op(PlaylistOp.Kind.ADD, 1, device = desktop, playlist = "pop",
                title = "shining through", duration = 308_000),
            op(PlaylistOp.Kind.REMOVE, 2, playlist = "pop",
                title = "shining through", duration = 308_271)
        )
        assertEquals(mapOf("pop" to emptyList<TrackKey>()), removed.playlists)

        // And the same in the favourites, where a heart is tapped on one device and untapped on
        // the other.
        val unhearted = replay(
            op(PlaylistOp.Kind.ADD, 1, device = desktop, title = "pulsar", duration = 214_000),
            op(PlaylistOp.Kind.REMOVE, 2, title = "pulsar", duration = 214_155)
        )
        assertEquals(emptyList<TrackKey>(), unhearted.favourites)
    }

    @Test
    fun aPlaylistFromTheOtherDeviceCanBeArrangedOnThisOne() {
        // The case the owner met: three tracks put into a playlist by the desktop, and the phone
        // arranging them. Every key in the order is the phone's spelling of a track the log holds
        // in the desktop's.
        val built = listOf(
            op(PlaylistOp.Kind.ADD, 1, device = desktop, playlist = "pop",
                title = "shining through", duration = 308_000),
            op(PlaylistOp.Kind.ADD, 2, device = desktop, playlist = "pop",
                title = "post mortem", duration = 354_000),
            op(PlaylistOp.Kind.ADD, 3, device = desktop, playlist = "pop",
                title = "dust and echoes", duration = 254_000)
        )
        val arranged = PlaylistOp(
            kind = PlaylistOp.Kind.ORDER,
            seq = 4,
            deviceId = phone,
            at = now,
            playlist = "pop",
            tracks = listOf(
                track("dust and echoes", 253_887),
                track("shining through", 308_271),
                track("post mortem", 353_441)
            )
        )
        // The order asked for, and the playlist still describing its tracks the way it did: a
        // device arranging a playlist does not get to rewrite what the tracks in it are called.
        val expected = mapOf(
            "pop" to listOf(
                track("dust and echoes", 254_000),
                track("shining through", 308_000),
                track("post mortem", 354_000)
            )
        )
        for (shuffled in shuffles(built + arranged)) {
            assertEquals(expected, PlaylistReplay.replay(shuffled).playlists)
        }
    }

    @Test
    fun theSameTrackAddedByBothDevicesIsInThePlaylistOnce() {
        val state = replay(
            op(PlaylistOp.Kind.ADD, 1, device = desktop, playlist = "pop",
                title = "shining through", duration = 308_000),
            op(PlaylistOp.Kind.ADD, 2, playlist = "pop",
                title = "shining through", duration = 308_271)
        )
        // Once, and under the description that got there first, so that what the playlist holds
        // does not change under it when the other device says the same thing again.
        assertEquals(mapOf("pop" to listOf(track("shining through", 308_000))), state.playlists)
    }

    @Test
    fun aSecondOfSlackIsAllTheSlackThereIs() {
        // Two readings of one track differ by a fraction of a second. Two different recordings of
        // the same song can differ by a second and a half, and those must stay apart — the rule
        // is [TrackKey.matches] and this is here to say that the replay really uses it rather
        // than something of its own that is looser.
        val state = replay(
            op(PlaylistOp.Kind.ADD, 1, device = desktop, playlist = "pop",
                title = "shining through", duration = 308_000),
            op(PlaylistOp.Kind.REMOVE, 2, playlist = "pop",
                title = "shining through", duration = 309_500)
        )
        assertEquals(mapOf("pop" to listOf(track("shining through", 308_000))), state.playlists)
    }
}
