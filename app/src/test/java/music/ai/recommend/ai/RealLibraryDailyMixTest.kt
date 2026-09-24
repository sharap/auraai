package music.ai.recommend.ai

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs two weeks of daily playlists over an exported library (ClusterExperimentTest#exportEmbeddings)
 * so the result can be read by eye: does it repeat itself, does one artist take over, how long does
 * a day take to build. Skipped unless AURAAI_EMBEDDINGS points at an export; the export holds
 * personal data and is not checked in.
 *
 * AURAAI_LISTENS=finish simulates a user who keeps finishing what the playlist offers, which is how
 * the profile is supposed to tighten over days rather than drift at random.
 */
class RealLibraryDailyMixTest {

    @Test
    fun printTwoWeeksOfDailyPlaylists() {
        val path = System.getenv("AURAAI_EMBEDDINGS")
        assumeTrue("set AURAAI_EMBEDDINGS to run", path != null && File(path).exists())

        val rows = File(path!!).readLines().map { it.split('\t') }
        val tracks = rows.mapIndexed { i, row ->
            MixTrack(
                id = i.toLong(),
                vector = row[3].split(',').map(String::toFloat).toFloatArray(),
                group = row[1].trim().ifEmpty { "?" }.lowercase(),
                duplicateKey = "${row[1].trim()}|${row[0].trim()}".lowercase()
            )
        }
        val label = { id: Long -> "${rows[id.toInt()][1].trim()} — ${rows[id.toInt()][0]}" }
        val simulateListening = System.getenv("AURAAI_LISTENS") == "finish"

        val day = 24L * 60 * 60 * 1000
        var now = 1_700_000_000_000L
        val listens = ArrayList<MixListen>()
        val lastPlayed = HashMap<Long, Long>()
        val previousDays = ArrayList<List<Long>>()

        for (d in 1..14) {
            val withHistory = tracks.map { it.copy(lastPlayedAt = lastPlayed[it.id]) }
            val recentlyOffered = previousDays.takeLast(7).flatten().toSet()
            val start = System.nanoTime()
            val mix = DailyMix.build(
                tracks = withHistory,
                listens = listens,
                favourites = emptySet(),
                recentlyOffered = recentlyOffered,
                seed = DailyMix.seedFor("2026-09-%02d".format(d), tracks.size),
                now = now
            )
            val ms = (System.nanoTime() - start) / 1_000_000

            val artists = mix.map { tracks[it.toInt()].group }.toSet().size
            val repeated = previousDays.flatten().toSet().let { seen -> mix.count { it in seen } }
            println("\nday $d: ${mix.size} tracks, $artists artists, $repeated seen before, $ms ms")
            mix.take(8).forEach { println("   ${label(it).take(78)}") }

            if (simulateListening) {
                // Half the playlist is listened through, a quarter is skipped.
                mix.forEachIndexed { i, id ->
                    when {
                        i % 4 == 3 -> listens += MixListen(id, now, 0.05f)
                        i % 2 == 0 -> {
                            listens += MixListen(id, now, 1f)
                            lastPlayed[id] = now
                        }
                    }
                }
            }
            previousDays += mix
            now += day
        }

        val all = previousDays.flatten()
        println("\n14 days: ${all.size} slots, ${all.toSet().size} distinct tracks " +
            "(${100 * all.toSet().size / all.size}% unique)")
    }
}
