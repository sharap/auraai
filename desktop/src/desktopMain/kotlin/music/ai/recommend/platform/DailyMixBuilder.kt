package music.ai.recommend.platform

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.ai.recommend.ai.DailyMix
import music.ai.recommend.ai.MixListen
import music.ai.recommend.ai.MixTrack
import music.ai.recommend.db.getAppDatabase
import music.ai.recommend.model.Song
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

actual class DailyMixBuilder actual constructor() {

    private val db = getAppDatabase()
    private val gson = Gson()
    private val baseDir = File(System.getProperty("user.home"), ".aimusic")
    private val file get() = File(baseDir, "daily_mix.json")

    private class Stored(
        val day: String = "",
        val attempt: Int = 0,
        val paths: List<String>? = null,
        val recent: List<Day>? = null,
        /** Which device built it, or null when this one did; see `sync/MixStore`. */
        val from: String? = null
    )

    private class Day(val day: String = "", val paths: List<String>? = null)

    actual suspend fun playlist(
        library: List<Song>,
        favourites: Set<String>,
        rebuild: Boolean
    ): DailyPlaylist = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        val stored = load()
        val byPath = library.associateBy { it.path }

        if (!rebuild && stored != null && stored.day == today) {
            val ids = stored.paths.orEmpty()
            val songs = ids.mapNotNull { byPath[it] }
            // Songs may have been deleted since; rebuild rather than show a half-empty playlist.
            if (songs.size >= ids.size - 2 && songs.isNotEmpty()) {
                return@withContext DailyPlaylist(today, songs)
            }
        }

        // Undecodable tracks are stored as zero vectors so the scan does not retry them; they
        // carry no direction, so every one of them would sit at the same distance from everything.
        val vectors = db.musicDao().getAllEmbeddings()
            .filter { entity -> entity.vector.any { it != 0f } }
            .associate { entity -> entity.path to FloatArray(entity.vector.size) { entity.vector[it] } }

        val analysed = library.filter { it.path in vectors }.distinctBy { it.path }
        if (analysed.size < MIN_LIBRARY) return@withContext DailyPlaylist(today, emptyList())

        val events = db.musicDao().playEvents()
        val lastPlayed = HashMap<String, Long>(events.size)
        for (event in events) {
            val previous = lastPlayed[event.path]
            if (previous == null || event.playedAt > previous) lastPlayed[event.path] = event.playedAt
        }
        val profileFrom = now - PROFILE_DAYS * DAY_MS
        val listens = events.filter { it.playedAt >= profileFrom }.map { event ->
            val fraction =
                if (event.durationMs <= 0) 0f
                else (event.playedMs.toFloat() / event.durationMs).coerceIn(0f, 1f)
            MixListen(event.path, event.playedAt, fraction)
        }

        val tracks = analysed.map { song ->
            MixTrack(
                path = song.path,
                vector = vectors.getValue(song.path),
                // The artist keeps one act from filling the playlist; with no artist tag the
                // folder is the next best thing, since libraries are usually filed by album.
                group = song.artist.trim().lowercase()
                    .takeIf { it.isNotEmpty() && it != "unknown artist" && it != "unknown" && it != "<unknown>" }
                    ?: song.folderName.lowercase(),
                lastPlayedAt = lastPlayed[song.path],
                duplicateKey = "${song.artist.trim()}|${song.title.trim()}".lowercase()
            )
        }

        // Whatever was stored last goes into the recent days — including a finished day, which is
        // the whole point of remembering them: yesterday's playlist must not come back today.
        val previousToday = stored?.takeIf { it.day == today }
        val attempt = if (previousToday != null) previousToday.attempt + 1 else 0
        val recent = buildList {
            if (stored != null) add(Day(stored.day, stored.paths.orEmpty()))
            addAll(stored?.recent.orEmpty().filter { it.day != stored?.day })
        }.take(REMEMBERED_DAYS)

        val paths = DailyMix.build(
            tracks = tracks,
            listens = listens,
            favourites = favourites,
            recentlyOffered = recent.flatMap { it.paths.orEmpty() }.toSet(),
            seed = DailyMix.seedFor(today, tracks.size) + attempt,
            now = now
        )
        // Built here, so [Stored.from] is left null: this machine is the one that may publish it.
        save(Stored(today, attempt, paths, recent))
        // Printed so that an empty playlist can be told from one that was never built.
        println(
            "DailyMix: $today -> ${paths.size} tracks from ${tracks.size} analysed, " +
                "${recent.size} recent days held back"
        )
        DailyPlaylist(today, paths.mapNotNull { byPath[it] })
    }

    actual fun today(): String = LocalDate.now().toString()

    actual suspend fun offered(): OfferedMix? = withContext(Dispatchers.IO) {
        val stored = load() ?: return@withContext null
        if (stored.day != LocalDate.now().toString()) return@withContext null
        val paths = stored.paths.orEmpty()
        if (paths.isEmpty()) return@withContext null
        OfferedMix(stored.day, stored.attempt, stored.from, paths)
    }

    actual suspend fun adopt(day: String, attempt: Int, from: String, paths: List<String>) {
        withContext(Dispatchers.IO) {
            val stored = load()
            val recent = buildList {
                if (stored != null && !stored.paths.isNullOrEmpty()) {
                    add(Day(stored.day, stored.paths))
                }
                addAll(stored?.recent.orEmpty().filter { it.day != stored?.day })
            }.take(REMEMBERED_DAYS)
            save(Stored(day, attempt, paths, recent, from))
            println("DailyMix: $day adopted from $from: ${paths.size} tracks, attempt $attempt")
        }
    }

    actual fun millisUntilNextDay(): Long {
        val zone = ZoneId.systemDefault()
        val nextMidnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return (nextMidnight - System.currentTimeMillis()).coerceAtLeast(1_000)
    }

    private fun load(): Stored? = runCatching {
        file.takeIf { it.exists() }?.reader()?.use { gson.fromJson(it, Stored::class.java) }
    }.getOrNull()

    private fun save(stored: Stored) {
        runCatching {
            if (!baseDir.exists()) baseDir.mkdirs()
            // Written aside and renamed, so a crash mid-write cannot leave a half-parsed file.
            val tmp = File(file.path + ".tmp")
            tmp.writer().use { gson.toJson(stored, it) }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { println("DailyMix: could not store the playlist: ${it.message}") }
    }

    private companion object {
        /** Below this there is nothing to choose from; the library is the playlist. */
        const val MIN_LIBRARY = 40

        /** How many previous days are kept out of today's playlist. */
        const val REMEMBERED_DAYS = 7

        /** How far back the taste profile looks. */
        const val PROFILE_DAYS = 90L

        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
