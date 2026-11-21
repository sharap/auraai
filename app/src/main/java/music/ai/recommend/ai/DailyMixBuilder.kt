package music.ai.recommend.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.sync.MixStore
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

    private class Stored(
        val day: String,
        val attempt: Int,
        val ids: List<Long>,
        val recent: List<Day>,
        /** Which device built it, or null when this one did; see [MixStore]. */
        val from: String? = null
    )
    private class Day(val day: String, val ids: List<Long>)

    /** Today's playlist as the sync folder needs to hear about it. */
    data class Offered(val day: String, val attempt: Int, val from: String?, val ids: List<Long>)

    /** The day the playlist is for, by the listener's own calendar. */
    fun today(now: Long = System.currentTimeMillis()): String = dayOf(now)

    /**
     * What is stored for today, or null when nothing is.
     *
     * Read off the disk rather than taken from what is on the screen, because the attempt and who
     * built it are part of the answer and only the stored form has them.
     */
    suspend fun offered(now: Long = System.currentTimeMillis()): Offered? =
        withContext(Dispatchers.IO) {
            val stored = load() ?: return@withContext null
            if (stored.day != dayOf(now)) return@withContext null
            if (stored.ids.isEmpty()) return@withContext null
            Offered(stored.day, stored.attempt, stored.from, stored.ids)
        }

    /**
     * Takes a playlist built on another device as today's.
     *
     * What was here before goes into the recent days, exactly as a rebuild would do with it: if
     * the owner asks for another mix later today, the one that was adopted should not come
     * straight back.
     */
    suspend fun adopt(day: String, attempt: Int, from: String, ids: List<Long>) {
        withContext(Dispatchers.IO) {
            val stored = load()
            val recent = buildList {
                if (stored != null && stored.ids.isNotEmpty()) add(Day(stored.day, stored.ids))
                addAll(stored?.recent.orEmpty().filter { it.day != stored?.day })
            }.take(REMEMBERED_DAYS)
            save(Stored(day, attempt, ids, recent, from))
            Log.i(TAG, "daily mix for $day adopted from $from: ${ids.size} tracks, attempt $attempt")
        }
    }

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
        val listens = history.recent().map { MixListen<Long>(it.track, it.playedAt, it.fraction) }
        val tracks = analysed.map { song ->
            MixTrack<Long>(
                track = song.id,
                vector = vectors.getValue(song.id),
                // The artist keeps one act from filling the playlist. Most of this library has
                // no artist tag, so it is read off the file name where it can be — see TrackGroups.
                group = TrackGroups.of(song.artist, song.title, song.folderName),
                lastPlayedAt = lastPlayed[song.id],
                // Named the same way, so one recording is caught whether or not it carries tags.
                duplicateKey =
                    "${TrackGroups.of(song.artist, song.title, song.folderName)}|" +
                        TrackGroups.songInName(song.title).lowercase()
            )
        }

        // Whatever was stored last goes into the recent days — including a finished day, which is
        // the whole point of remembering them: yesterday's playlist must not come back today.
        val previousToday = stored?.takeIf { it.day == today }
        val attempt = if (previousToday != null) previousToday.attempt + 1 else 0
        val recent = buildList {
            if (stored != null) add(Day(stored.day, stored.ids))
            addAll(stored?.recent.orEmpty().filter { it.day != stored?.day })
        }.take(REMEMBERED_DAYS)

        val ids = DailyMix.build(
            tracks = tracks,
            listens = listens,
            favourites = favourites,
            recentlyOffered = recent.flatMap { it.ids }.toSet(),
            seed = DailyMix.seedFor(today, tracks.size) + attempt,
            now = now
        )
        // Built here, so [Stored.from] is left null: this device is the one that may publish it.
        save(Stored(today, attempt, ids, recent))
        // Logged at info so that an empty playlist can be told from a playlist that was never
        // built, without attaching a debugger to a release build.
        Log.i(TAG, "daily mix for $today: ${ids.size} tracks from ${tracks.size} analysed, " +
            "${recent.size} recent days held back")
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
