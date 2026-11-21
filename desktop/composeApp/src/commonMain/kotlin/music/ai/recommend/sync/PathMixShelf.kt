package music.ai.recommend.sync

import music.ai.recommend.model.Song
import music.ai.recommend.platform.DailyMixBuilder

/**
 * This app's playlist of the day, as [SyncCoordinator] needs to see it.
 *
 * Thin, like the other shelves: the record, the comparison and the matching are shared code, and
 * what is left for the platform is where the mix is kept and what a track is called here — a path,
 * which means nothing on the other device.
 *
 * Not a copy, and not even the same name: the Android app's answer to the same question is
 * `MixShelf` over MediaStore ids. A file of one name on both sides is a file that must be a copy
 * of itself, and these two cannot be.
 */
class PathMixShelf(
    private val builder: DailyMixBuilder,
    private val songs: suspend () -> List<Song>,
    /** Where an adopted mix goes: the view model shows this as today's playlist. */
    private val onShow: suspend (List<Song>) -> Unit
) : MixStore<String> {

    override suspend fun today(): String = builder.today()

    override suspend fun current(): MixStore.Offered<String>? {
        val offered = builder.offered() ?: return null
        return MixStore.Offered(offered.day, offered.attempt, offered.from, offered.paths)
    }

    override suspend fun adopt(day: String, attempt: Int, from: String, tracks: List<String>) {
        builder.adopt(day, attempt, from, tracks)
        val byPath = songs().associateBy { it.path }
        onShow(tracks.mapNotNull { byPath[it] })
    }
}
