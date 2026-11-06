package music.ai.recommend.sync

import music.ai.recommend.ai.DailyMixBuilder
import music.ai.recommend.model.Song

/**
 * This app's playlist of the day, as [SyncCoordinator] needs to see it.
 *
 * Thin, like the other shelves: the record, the comparison and the matching are shared code, and
 * what is left for the platform is where the mix is kept and what a track is called here — a
 * MediaStore id, which means nothing on the other device.
 *
 * Not a copy. The desktop port's answer to the same question is `MixShelf` over paths.
 */
class MixShelf(
    private val builder: DailyMixBuilder,
    private val songs: suspend () -> List<Song>,
    /** Where an adopted mix goes: the view model shows this as today's playlist. */
    private val onShow: suspend (List<Song>) -> Unit
) : MixStore<Long> {

    override suspend fun today(): String = builder.today()

    override suspend fun current(): MixStore.Offered<Long>? {
        val offered = builder.offered() ?: return null
        return MixStore.Offered(offered.day, offered.attempt, offered.from, offered.ids)
    }

    override suspend fun adopt(day: String, attempt: Int, from: String, tracks: List<Long>) {
        builder.adopt(day, attempt, from, tracks)
        val byId = songs().associateBy { it.id }
        onShow(tracks.mapNotNull { byId[it] })
    }
}
