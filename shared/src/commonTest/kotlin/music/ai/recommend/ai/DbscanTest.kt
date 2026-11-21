package music.ai.recommend.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class DbscanTest {

    private val dims = 64

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun randomUnit(random: Random) = normalize(FloatArray(dims) { random.nextFloat() * 2 - 1 })

    private fun blob(centre: FloatArray, count: Int, spread: Float, random: Random) = List(count) {
        normalize(FloatArray(centre.size) { centre[it] + (random.nextFloat() * 2 - 1) * spread })
    }

    private fun dbscan(points: List<FloatArray>, eps: Float): IntArray =
        SmartAlbumClustering.dbscan(SmartAlbumClustering.sortedNeighbours(points, eps), eps)

    @Test
    fun findsSeparatedGroupsAndLeavesOutliersAsNoise() {
        val random = Random(42)
        val centres = List(3) { randomUnit(random) }
        val points = centres.flatMap { blob(it, 15, 0.05f, random) } + List(6) { randomUnit(random) }

        val labels = dbscan(points, eps = 0.05f)

        assertEquals("three groups", 3, labels.filter { it != SmartAlbumClustering.NOISE }.toSet().size)
        for (group in 0 until 3) {
            val groupLabels = labels.slice(group * 15 until (group + 1) * 15).toSet()
            assertEquals("blob $group split or merged", 1, groupLabels.size)
            assertTrue(groupLabels.single() != SmartAlbumClustering.NOISE)
        }
        assertTrue(labels.takeLast(6).all { it == SmartAlbumClustering.NOISE })
    }

    /**
     * A border point — within reach of both clusters but with too few neighbours to be a core point
     * itself — joins one of them and does not expand, so it cannot merge the two. (A core point in
     * the same place would merge them; that chaining is how DBSCAN is defined.) Built on the unit
     * circle so the geometry is exact: groups at 0-8° and 60-68°, the bridge at 34°, and eps equal
     * to 27°, which reaches exactly one point of each group from the bridge.
     */
    @Test
    fun aBorderPointCannotBridgeTwoClusters() {
        fun at(degrees: Double) = floatArrayOf(
            cos(Math.toRadians(degrees)).toFloat(),
            sin(Math.toRadians(degrees)).toFloat()
        )
        val points = listOf(0.0, 2.0, 4.0, 6.0, 8.0).map(::at) +
            listOf(60.0, 62.0, 64.0, 66.0, 68.0).map(::at) +
            listOf(at(34.0))
        val eps = (1 - cos(Math.toRadians(27.0))).toFloat()

        val labels = dbscan(points, eps)

        assertEquals(1, labels.take(5).toSet().size)
        assertEquals(1, labels.slice(5 until 10).toSet().size)
        assertTrue("the groups stayed apart", labels[0] != labels[5])
        assertTrue("the bridge joined a group", labels[10] != SmartAlbumClustering.NOISE)
    }

    /** Neighbours computed once and read as prefixes must give the same answer as fresh ones. */
    @Test
    fun prefixNeighboursMatchFreshNeighbours() {
        val random = Random(5)
        val points = List(3) { randomUnit(random) }.flatMap { blob(it, 12, 0.08f, random) }
        val wide = SmartAlbumClustering.sortedNeighbours(points, 0.3f)
        for (eps in listOf(0.02f, 0.05f, 0.1f, 0.2f)) {
            val fromPrefix = SmartAlbumClustering.dbscan(wide, eps)
            val fresh = SmartAlbumClustering.dbscan(SmartAlbumClustering.sortedNeighbours(points, eps), eps)
            assertTrue("eps=$eps", fromPrefix.contentEquals(fresh))
        }
    }

    /** PCA must recover a planted low-dimensional structure and order it by variance. */
    @Test
    fun principalComponentsFindThePlantedDirections() {
        val random = Random(11)
        val d = 40
        val strong = normalize(FloatArray(d) { random.nextFloat() - 0.5f })
        val weak = normalize(FloatArray(d) { random.nextFloat() - 0.5f }.let { w ->
            val p = w.indices.sumOf { (w[it] * strong[it]).toDouble() }.toFloat()
            FloatArray(d) { w[it] - p * strong[it] } // orthogonal to the strong one
        })
        val data = List(300) {
            val a = (random.nextFloat() - 0.5f) * 10f
            val b = (random.nextFloat() - 0.5f) * 3f
            FloatArray(d) { i -> a * strong[i] + b * weak[i] + (random.nextFloat() - 0.5f) * 0.05f }
        }
        val mean = FloatArray(d) { i -> data.map { it[i] }.average().toFloat() }
        val centred = data.map { v -> FloatArray(d) { v[it] - mean[it] } }

        val pca = SmartAlbumClustering.principalComponents(centred, 4)

        fun alignment(u: FloatArray, v: FloatArray) = kotlin.math.abs(SmartAlbumClustering.dot(u, v))
        assertTrue("first component ${alignment(pca.components[0], strong)}", alignment(pca.components[0], strong) > 0.99f)
        assertTrue("second component ${alignment(pca.components[1], weak)}", alignment(pca.components[1], weak) > 0.99f)
        assertTrue(pca.eigenvalues[0] > pca.eigenvalues[1])
    }

    /**
     * The failure the pipeline exists to avoid: groups that share a large common component look
     * alike on raw cosine distance and chain into one blob. After centring and PCA they separate.
     */
    @Test
    fun separatesGroupsHiddenUnderASharedComponent() {
        val random = Random(21)
        val d = 128
        val shared = normalize(FloatArray(d) { random.nextFloat() - 0.5f })
        val offsets = List(5) { randomUnit(Random(100 + it)).copyOf(d).let(::normalize) }
        val points = offsets.flatMap { offset ->
            List(12) {
                // 90% shared "this is music", 10% what actually distinguishes the group.
                normalize(FloatArray(d) { i ->
                    3f * shared[i] + 0.6f * offset[i] + (random.nextFloat() - 0.5f) * 0.15f
                })
            }
        }

        val albums = SmartAlbumClustering.cluster(points)

        assertEquals("albums: ${albums.map { it.size }}", 5, albums.size)
        for (album in albums) {
            val groupsInAlbum = album.map { it / 12 }.toSet()
            assertEquals("an album mixed groups $groupsInAlbum", 1, groupsInAlbum.size)
        }
    }

    /** Below 1 the radius only shrinks, so albums can only lose tracks; 1 is exactly automatic. */
    @Test
    fun epsScaleTightensAlbums() {
        val random = Random(8)
        val points = List(6) { randomUnit(random) }.flatMap { blob(it, 15, 0.12f, random) } + List(20) { randomUnit(random) }
        val auto = SmartAlbumClustering.cluster(points)
        assertEquals(auto.map { it.toList() }, SmartAlbumClustering.cluster(points, epsScale = 1f).map { it.toList() })
        val tight = SmartAlbumClustering.cluster(points, epsScale = 0.6f)
        assertTrue("tight ${tight.sumOf { it.size }} vs auto ${auto.sumOf { it.size }}",
            tight.sumOf { it.size } <= auto.sumOf { it.size })
        // Far outside the range it is clamped rather than turning into one blob or nothing.
        assertTrue(SmartAlbumClustering.cluster(points, epsScale = 10f).isNotEmpty())
    }

    @Test
    fun minPointsGrowsWithTheLibrary() {
        assertEquals(4, SmartAlbumClustering.minPointsFor(30))
        assertEquals(5, SmartAlbumClustering.minPointsFor(161))
        assertEquals(8, SmartAlbumClustering.minPointsFor(2000))
        assertEquals(8, SmartAlbumClustering.minPointsFor(4308))
        assertEquals(12, SmartAlbumClustering.minPointsFor(1_000_000))
    }

    @Test
    fun capIsClampedToAlbumSizes() {
        assertEquals(20, SmartAlbumClustering.albumCap(50))
        assertEquals(30, SmartAlbumClustering.albumCap(200))
        assertEquals(60, SmartAlbumClustering.albumCap(2000))
    }
}
