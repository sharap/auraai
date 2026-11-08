package music.ai.recommend.sync

import music.ai.recommend.history.PlayEvent
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.model.Song

/**
 * This app's listening history, as [SyncCoordinator] wants to see it.
 *
 * All of what is particular to Android lives here: history is a Room table, a track is a MediaStore
 * id, and the library comes from whoever scanned it last. None of that reaches the ordering.
 */
class PlayHistoryStore(
    private val history: PlayHistory,
    private val songs: suspend () -> List<Song>,
    /**
     * The print of each file's sound, for the tracks this device has taken one of.
     *
     * The key carries it when it is known, and then identity stops being a judgement call about
     * spelling: two copies of one recording agree however their tags were written, and two
     * different recordings under one name stop being the same track. Null for a file nobody has
     * hashed yet, which is the honest answer and the one the tags are for.
     */
    private val prints: suspend () -> Map<Long, String> = { emptyMap() }
) : HistoryStore<Long> {

    override suspend fun own(afterId: Long, limit: Int): List<OwnListen<Long>> =
        history.notYetExported(afterId, limit).map {
            OwnListen(
                rowId = it.rowId,
                track = it.event.songId,
                playedAt = it.event.playedAt,
                playedMs = it.event.playedMs,
                durationMs = it.event.durationMs
            )
        }

    override suspend fun takeIn(deviceId: String, listens: List<TakenListen<Long>>): Int =
        history.recordFromOtherDevice(deviceId, listens.map {
            PlayEvent(it.track, it.playedAt, it.playedMs, it.durationMs)
        })

    override suspend fun heldFromOthers(now: Long): Set<HistoryMerge.Known> =
        history.fromOtherDevices(now = now)
            .map { (device, at) -> HistoryMerge.Known(device, at) }
            .toSet()

    override suspend fun library(): Map<Long, TrackKey> {
        val library = songs()
        val known = prints()
        val keys = LinkedHashMap<Long, TrackKey>(library.size * 2)
        for (song in library) {
            keys[song.id] = TrackKey.describe(
                artist = song.artist,
                title = song.title,
                fileName = song.path.substringAfterLast('/'),
                durationMs = song.duration,
                hash = known[song.id]
            )
        }
        return keys
    }

    override fun asText(track: Long): String = track.toString()

    override fun fromText(text: String): Long? = text.toLongOrNull()
}
