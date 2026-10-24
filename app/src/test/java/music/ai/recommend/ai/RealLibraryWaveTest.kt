package music.ai.recommend.ai

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Runs a wave over an exported library (ClusterExperimentTest#exportEmbeddings) with a simulated
 * listener, so the stream can be read by eye: does it stay on one thing, does it get out of a
 * neighbourhood that is being skipped, does one artist take over. Skipped unless AURAAI_EMBEDDINGS
 * points at an export; the export holds personal data and is not checked in.
 */
class RealLibraryWaveTest {

    @Test
    fun printAWaveThatIsBeingSkippedAwayFrom() {
        val path = System.getenv("AURAAI_EMBEDDINGS")
        assumeTrue("set AURAAI_EMBEDDINGS to run", path != null && File(path).exists())

        val rows = File(path!!).readLines().map { it.split('\t') }
        val tracks = rows.mapIndexed { i, row ->
            MixTrack(
                id = i.toLong(),
                vector = row[3].split(',').map(String::toFloat).toFloatArray(),
                group = row[1].trim().ifEmpty { "?" }.lowercase()
            )
        }
        val label = { id: Long -> "${rows[id.toInt()][1].trim()} — ${rows[id.toInt()][0]}" }
        val folderOf = { id: Long -> rows[id.toInt()][2] }

        // The listener starts the wave from one track and then skips everything that comes from
        // the same folder as that track, hearing out everything else — the hardest case for the
        // steering, since the stream is pointed straight at what is being refused.
        val seedTrack = tracks[tracks.size / 3]
        val refusedFolder = folderOf(seedTrack.id)
        var state = Wave.start(seedTrack.vector, tracks)
        val random = Random(17)
        val played = ArrayList<Long>()
        var skips = 0

        val start = System.nanoTime()
        repeat(40) {
            val (next, queued) = Wave.next(state, tracks, count = 1, current = null, random = random)
            val track = next.singleOrNull() ?: return@repeat
            state = queued
            val refused = folderOf(track.id) == refusedFolder
            if (refused) skips++
            state = Wave.react(state, track, fraction = if (refused) 0.03f else 1f)
            played += track.id
        }
        val ms = (System.nanoTime() - start) / 1_000_000

        println("\n40 tracks in $ms ms, $skips skipped, refused folder: $refusedFolder")
        println("first ten:")
        played.take(10).forEach { println("   ${if (folderOf(it) == refusedFolder) "✗" else " "} ${label(it).take(74)}") }
        println("last ten:")
        played.takeLast(10).forEach { println("   ${if (folderOf(it) == refusedFolder) "✗" else " "} ${label(it).take(74)}") }

        val firstHalf = played.take(20).count { folderOf(it) == refusedFolder }
        val secondHalf = played.drop(20).count { folderOf(it) == refusedFolder }
        println("refused tracks served: $firstHalf in the first twenty, $secondHalf in the second")
        println("distinct artists: ${played.map { tracks[it.toInt()].group }.toSet().size} of ${played.size}")
    }
}
