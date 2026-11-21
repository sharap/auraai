package music.ai.recommend.ai

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One ten-second window against three, judged by the albums the production clustering makes.
 *
 * Why it exists: the album count and the coverage of a single clustering turned out to be a noisy
 * measure. Two runs over the same vectors, differing by one track out of 4526 because a decoder
 * stumbled on it, gave 37 albums and 42 — the eps is chosen by a search in discrete steps over the
 * quantiles of the pairwise distances, so a hair's difference in the cloud can move it a whole
 * step. Comparing two strategies by one run each therefore says as much about which run it was as
 * about the strategies.
 *
 * So the comparison is paired and repeated: the same random nine tenths of the library is clustered
 * with both sets of vectors, and what is reported is the distribution over subsets and how many of
 * them each strategy wins. Skipped unless both exports are named; they hold personal data and are
 * not checked in. Produce them the way `RealLibraryClusteringTest` wants its own input, one file
 * per strategy, with the same tracks in the same order.
 */
class WindowStrategyExperimentTest {

    @Test
    fun threeWindowsAgainstOne() {
        val onePath = System.getenv("AURAAI_ONE")
        val threePath = System.getenv("AURAAI_THREE")
        assumeTrue("set AURAAI_ONE and AURAAI_THREE to run", onePath != null && threePath != null)
        assumeTrue(File(onePath!!).exists() && File(threePath!!).exists())

        val one = read(File(onePath))
        val three = read(File(threePath))
        require(one.size == three.size) { "the two exports must hold the same tracks" }

        val subsets = System.getenv("AURAAI_SUBSETS")?.toIntOrNull() ?: 20
        val share = System.getenv("AURAAI_SHARE")?.toDoubleOrNull() ?: 0.9
        val random = Random(System.getenv("AURAAI_SEED")?.toLongOrNull() ?: 20261121L)

        val results = ArrayList<Pair<Measure, Measure>>(subsets)
        repeat(subsets) { round ->
            val picked = one.indices.shuffled(random).take((one.size * share).toInt()).sorted()
            val a = measure(picked.map { one[it] })
            val b = measure(picked.map { three[it] })
            results += a to b
            println(
                "subset ${round + 1}: one window ${a}, three windows ${b}"
            )
        }

        println("\n${results.size} subsets of ${(one.size * share).toInt()} tracks each")
        report("albums", results) { it.albums.toDouble() }
        report("covered tracks", results) { it.covered.toDouble() }
        report("purity by genre tag", results) { it.purity }
    }

    private fun report(name: String, results: List<Pair<Measure, Measure>>, of: (Measure) -> Double) {
        val ones = results.map { of(it.first) }
        val threes = results.map { of(it.second) }
        val diffs = ones.indices.map { threes[it] - ones[it] }
        val wins = diffs.count { it > 0 }
        val ties = diffs.count { it == 0.0 }
        println(
            "$name: one window ${mean(ones).format()} ± ${sd(ones).format()}, " +
                "three ${mean(threes).format()} ± ${sd(threes).format()}, " +
                "difference ${mean(diffs).format()} ± ${sd(diffs).format()}, " +
                "three wins in $wins of ${diffs.size}${if (ties > 0) " ($ties tied)" else ""}"
        )
    }

    private class Measure(val albums: Int, val covered: Int, val purity: Double) {
        override fun toString() = "$albums albums, $covered covered, purity ${"%.3f".format(purity)}"
    }

    private fun measure(rows: List<Row>): Measure {
        val vectors = rows.map { it.vector }
        val albums = SmartAlbumClustering.cluster(
            vectors,
            SmartAlbumClustering.minPointsFor(vectors.size),
            1f
        )
        var tagged = 0
        var right = 0
        for (album in albums) {
            val tags = album.map { rows[it].genre }.filter { it.isNotEmpty() && it != "—" }
            tagged += tags.size
            right += tags.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        }
        return Measure(albums.size, albums.sumOf { it.size }, if (tagged == 0) 0.0 else right.toDouble() / tagged)
    }

    private class Row(val genre: String, val vector: FloatArray)

    private fun read(file: File): List<Row> = file.readLines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 4) return@mapNotNull null
        Row(parts[2].trim(), parts[3].split(',').map(String::toFloat).toFloatArray())
    }

    private fun mean(values: List<Double>) = values.sum() / values.size

    private fun sd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    private fun Double.format() = "%.3f".format(this)
}
