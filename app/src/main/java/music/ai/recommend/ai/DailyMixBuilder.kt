package music.ai.recommend.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.model.Song
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The playlist of the day, as the UI needs it. */
data class DailyPlaylist(val day: String, val songs: List<Song>)

/**
 * Builds and remembers the playlist of the day.
 *
 * The playlist is fixed for the day: built once, kept on disk, and the same on every launch until
 * the date changes. That is deliberate — a playlist that quietly reshuffled itself every time the
 * app opened would be a shuffle button with extra steps, and there would be no point in coming
 * back to it later in the day.
 *
 * The days before today are kept too, so the same tracks are not offered again all week.
 */
class DailyMixBuilder(
    private val context: Context,
    private val embeddings: EmbeddingStore,
    private val history: PlayHistory
) {
    private val gson = Gson()
    private val file get() = File(context.filesDir, "daily_mix.json")

    private class Stored(val day: String, val attempt: Int, val ids: List<Long>, val recent: List<Day>)
    private class Day(val day: String, val ids: List<Long>)

    /**
     * @param rebuild builds a different playlist for today, on request. It replaces today's and
     *   the old one goes into the recent days, so the same tracks do not come straight back.
     */
    suspend fun playlist(
        library: List<Song>,
        favourites: Set<Long>,
        rebuild: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): DailyPlaylist = withContext(Dispatchers.Default) {
        val today = dayOf(now)
        val stored = load()
        val byId = library.associateBy { it.id }

        if (!rebuild && stored != null && stored.day == today) {
            val songs = stored.ids.mapNotNull { byId[it] }
            // Songs may have been deleted since; rebuild rather than show a half-empty playlist.
            if (songs.size >= stored.ids.size - 2 && songs.isNotEmpty()) {
                return@withContext DailyPlaylist(today, songs)
            }
        }

        val vectors = embeddings.all()
        val analysed = library.filter { it.id in vectors }.distinctBy { it.id }
        if (analysed.size < MIN_LIBRARY) return@withContext DailyPlaylist(today, emptyList())

        val lastPlayed = history.lastPlayed()
        val listens = history.recent().map { MixListen(it.songId, it.playedAt, it.fraction) }
        val tracks = analysed.map { song ->
            MixTrack(
                id = song.id,
                vector = vectors.getValue(song.id),
                // The artist keeps one act from filling the playlist; with no artist tag the
                // folder is the next best thing, since libraries are usually filed by album.
                group = song.artist.trim().lowercase()
                    .takeIf { it.isNotEmpty() && it != "<unknown>" }
                    ?: song.folderName.lowercase(),
                lastPlayedAt = lastPlayed[song.id],
                duplicateKey = "${song.artist.trim()}|${song.title.trim()}".lowercase()
            )
        }

        val previousToday = stored?.takeIf { it.day == today }
        val attempt = if (previousToday != null) previousToday.attempt + 1 else 0
        val recent = buildList {
            if (previousToday != null) add(Day(today, previousToday.ids))
            addAll(stored?.recent.orEmpty().filter { it.day != today })
        }.take(REMEMBERED_DAYS)

        val ids = DailyMix.build(
            tracks = tracks,
            listens = listens,
            favourites = favourites,
            recentlyOffered = recent.flatMap { it.ids }.toSet(),
            seed = DailyMix.seedFor(today, tracks.size) + attempt,
            now = now
        )
        save(Stored(today, attempt, ids, recent))
        Log.d(TAG, "daily mix for $today: ${ids.size} of ${tracks.size} analysed tracks")
        DailyPlaylist(today, ids.mapNotNull { byId[it] })
    }

    /** The local calendar date, so the playlist turns over at the listener's midnight. */
    private fun dayOf(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now))

    /** Milliseconds until the next local midnight, when the playlist should be rebuilt. */
    fun millisUntilNextDay(now: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (calendar.timeInMillis - now).coerceAtLeast(1_000)
    }

    private fun load(): Stored? = runCatching {
        file.takeIf { it.exists() }?.reader()?.use { gson.fromJson(it, Stored::class.java) }
    }.getOrNull()

    private fun save(stored: Stored) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writer().use { gson.toJson(stored, it) }
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Could not store the daily mix", it) }
    }

    private companion object {
        const val TAG = "DailyMix"

        /** Below this there is nothing to choose from; the library is the playlist. */
        const val MIN_LIBRARY = 40

        /** How many previous days are kept out of today's playlist. */
        const val REMEMBERED_DAYS = 7
    }
}
