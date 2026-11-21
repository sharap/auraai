package music.ai.recommend.sync

import music.ai.recommend.db.MusicDao
import music.ai.recommend.db.PlayEventEntity
import music.ai.recommend.model.Song

/**
 * This app's listening history, as [SyncCoordinator] wants to see it.
 *
 * All of what is particular to this port lives here: history is a log in a file, a track is the
 * path of the file playing, and the library comes from whoever scanned it last. None of that
 * reaches the ordering, which is shared with the phone.
 *
 * Not a copy. The Android app's answer to the same question is `PlayHistoryStore`, over a Room
 * table keyed by MediaStore id.
 */
class PlayLogStore(
    private val dao: MusicDao,
    private val songs: suspend () -> List<Song>,
    /** How long this app keeps history, which is as far back as a merge looks. */
    private val keepDays: Long = 365,
    /**
     * The print of each file's sound, for the tracks this device has taken one of.
     *
     * The key carries it when it is known, and then identity stops being a judgement call about
     * spelling: two copies of one recording agree however their tags were written, and two
     * different recordings under one name stop being the same track. Null for a file nobody has
     * hashed yet, which is the honest answer and the one the tags are for.
     */
    private val prints: suspend () -> Map<String, String> = { emptyMap() }
) : HistoryStore<String> {

    override suspend fun own(afterId: Long, limit: Int): List<OwnListen<String>> =
        dao.playEventsToExport(afterId, limit).map {
            OwnListen(
                rowId = it.id,
                track = it.path,
                playedAt = it.playedAt,
                playedMs = it.playedMs,
                durationMs = it.durationMs
            )
        }

    override suspend fun takeIn(deviceId: String, listens: List<TakenListen<String>>): Int {
        if (deviceId.isEmpty() || listens.isEmpty()) return 0
        return dao.appendPlayEvents(listens.map {
            PlayEventEntity(
                path = it.track,
                playedAt = it.playedAt,
                playedMs = it.playedMs,
                durationMs = it.durationMs,
                deviceId = deviceId
            )
        })
    }

    override suspend fun heldFromOthers(now: Long): Set<HistoryMerge.Known> =
        dao.playEventsFromOtherDevices(now - keepDays * DAY_MS)
            .map { HistoryMerge.Known(it.deviceId, it.playedAt) }
            .toSet()

    override suspend fun library(): Map<String, TrackKey> {
        val library = songs()
        val known = prints()
        val keys = LinkedHashMap<String, TrackKey>(library.size * 2)
        for (song in library) {
            // Keyed by path, which is what a listen here is recorded against. Two copies of one
            // file are therefore two tracks, and a listen taken in resolves to whichever the
            // matcher picked — the same track either way, as far as anything that reads history
            // is concerned.
            keys[song.path] = TrackKey.describe(
                artist = song.artist,
                title = song.title,
                fileName = song.path.substringAfterLast('/'),
                durationMs = song.duration,
                hash = known[song.path]
            )
        }
        return keys
    }

    /** A track is already text here, which is the one place this port has it easy. */
    override fun asText(track: String): String = track

    override fun fromText(text: String): String? = text.ifEmpty { null }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
