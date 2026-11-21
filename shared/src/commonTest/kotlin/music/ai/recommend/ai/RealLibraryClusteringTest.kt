package music.ai.recommend.ai

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the production pipeline on an exported library (ClusterExperimentTest#exportEmbeddings) so
 * its albums can be read by eye and compared with the offline analysis. Skipped unless
 * AURAAI_EMBEDDINGS points at an export; the export holds personal data and is not checked in.
 */
class RealLibraryClusteringTest {

    @Test
    fun printAlbumsForAnExportedLibrary() {
        val path = System.getenv("AURAAI_EMBEDDINGS")
        assumeTrue("set AURAAI_EMBEDDINGS to run", path != null && File(path).exists())

        val rows = File(path!!).readLines().map { it.split('\t') }
        val labels = rows.map { "${it[1].trim()} — ${it[0]}  [${it[2]}]" }
        val vectors = rows.map { row -> row[3].split(',').map(String::toFloat).toFloatArray() }

        val start = System.nanoTime()
        val minPoints = System.getenv("AURAAI_MINPTS")?.toIntOrNull()
            ?: SmartAlbumClustering.minPointsFor(vectors.size)
        val epsScale = System.getenv("AURAAI_EPS_SCALE")?.toFloatOrNull() ?: 1f
        val albums = SmartAlbumClustering.cluster(vectors, minPoints, epsScale)
        val ms = (System.nanoTime() - start) / 1_000_000

        // Optional: name the albums with label embeddings from ClusterExperimentTest#exportLabelEmbeddings.
        val names = System.getenv("AURAAI_LABELS")?.let { File(it) }?.takeIf { it.exists() }?.let { file ->
            val labelRows = file.readLines().map { it.split('\t') }
            val labelVectors = labelRows.map { row -> row[2].split(',').map(String::toFloat).toFloatArray() }
            val library = SmartAlbumClustering.centroid(vectors, IntArray(vectors.size) { it })
            val chosen = SmartAlbumNaming.bestLabels(albums.map { SmartAlbumClustering.centroid(vectors, it) }, library, labelVectors)
            SmartAlbumNaming.uniqueTitles(
                chosen.map { labelRows[it][1] },
                albums.map { album -> SmartAlbumNaming.dominantArtist(album.map { rows[it][1] to rows[it][0] }) }
            )
        }

        System.getenv("AURAAI_ALBUMS_OUT")?.let { out ->
            File(out).writeText(albums.joinToString("\n") { it.joinToString(" ") })
        }

        val covered = albums.sumOf { it.size }
        println("minPts=$minPoints eps×$epsScale ${vectors.size} tracks -> ${albums.size} albums ${albums.map { it.size }}, " +
            "covering $covered (${100 * covered / vectors.size}%), ${ms} ms")
        for ((i, album) in albums.withIndex()) {
            val artists = SmartAlbumNaming.leadingArtistsOf(album.map { rows[it][1] to rows[it][0] })
            println("\nalbum $i «${names?.get(i) ?: "?"}» (${album.size}) — $artists")
            album.take(6).forEach { println("   ${labels[it].take(80)}") }
        }
    }
}
