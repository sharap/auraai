package music.ai.recommend.ai

import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Groups a library into "smart albums" with DBSCAN.
 *
 * DBSCAN on the raw CLAP embeddings does not work, and the reasons shaped everything here. Measured
 * on a real 161-track library:
 *
 *  - **Raw embeddings chain into one blob.** Every CLAP embedding shares a large "this is music"
 *    component, so the library is a continuous cloud: no eps gives more than one real cluster —
 *    small eps is all noise, and a larger one grows a single cluster until it has swallowed ~90%.
 *    Centring the embeddings and keeping only the principal components that carry most of the
 *    variance removes the shared component and exposes the structure underneath (8 dimensions
 *    carried 60% of the variance there).
 *  - **The textbook eps heuristic picks the wrong eps.** The knee of the k-distance curve marks
 *    where outliers begin, so it makes nearly every point a core point — which on a continuum
 *    means one blob, in every representation tried. Instead eps is chosen for what the feature
 *    needs: the value that puts the most tracks into album-sized groups. That objective climbs as
 *    groups form and collapses the moment they merge into something too big to be an album, so
 *    its maximum sits just before the merge.
 *
 * The result there was 10 albums of 4-18 tracks — trance, reggaeton/party, big-room, progressive
 * house, pop-dance remixes — covering half the library. The other half is DBSCAN's noise: tracks
 * that are not in any dense group, which is the honest answer for them.
 *
 * Pure Kotlin, no Android dependencies, so the whole pipeline runs under JVM unit tests.
 */
object SmartAlbumClustering {

    const val NOISE = -1

    /** A group smaller than this is not worth calling an album. Also DBSCAN's minPoints. */
    const val MIN_ALBUM_SIZE = 4

    private const val MAX_MIN_POINTS = 12

    /** Share of the variance the kept principal components must carry. */
    private const val VARIANCE_TO_KEEP = 0.6
    private const val MIN_DIMENSIONS = 4
    private const val MAX_DIMENSIONS = 12

    /** eps candidates span these quantiles of the pairwise distances. */
    private const val LOWEST_QUANTILE = 0.001
    private const val HIGHEST_QUANTILE = 0.15
    private const val EPS_STEPS = 40
    private const val REFINE_STEPS = 24

    /** Once one group holds this share of the points, larger eps only grows the same blob. */
    private const val TAKEN_OVER = 0.6

    /** Groups larger than the cap are split again, at most this many times over. */
    private const val MAX_DEPTH = 2

    /** Pairs sampled to estimate distance quantiles, so large libraries do not store all pairs. */
    private const val QUANTILE_SAMPLE = 200_000

    /**
     * Largest group still treated as an album: 15% of the library, clamped to 20-60 tracks. Bigger
     * than that it is a genre-sized blob, not something anyone would play as an album.
     */
    fun albumCap(librarySize: Int): Int = (0.15 * librarySize).toInt().coerceIn(20, 60)

    /** Range of the user's eps multiplier; 1 is the automatically chosen eps. */
    const val MIN_EPS_SCALE = 0.5f
    const val MAX_EPS_SCALE = 1.5f

    /**
     * @param vectors unit-length embeddings; zero vectors (undecodable tracks) must be left out.
     * @param epsScale multiplies the eps chosen for every group. An absolute eps would mean little:
     *   it is chosen per group, in that group's own reduced space, and its scale differs from one
     *   library to the next. Below 1 albums get tighter and fewer tracks make it in; above 1 they
     *   take in more, until they outgrow the cap and are split again.
     * @return albums as indices into [vectors], largest first. Tracks in no album are simply absent.
     */
    fun cluster(
        vectors: List<FloatArray>,
        minPoints: Int = minPointsFor(vectors.size),
        epsScale: Float = 1f
    ): List<IntArray> {
        if (vectors.size < minPoints * 2) return emptyList()
        val all = IntArray(vectors.size) { it }
        val scale = epsScale.coerceIn(MIN_EPS_SCALE, MAX_EPS_SCALE)
        return clusterSubset(vectors, all, albumCap(vectors.size), minPoints, scale, depth = 0)
            .sortedByDescending { it.size }
    }

    /**
     * minPoints grows with the library, roughly as ln(n). With a fixed 4, a 4308-track library came
     * out as 106 albums, half of them four or five tracks — chance clumps that any large sample
     * contains. ln(n) gave 43 albums with a median of 17 there, and 7 albums of 5-15 tracks on a
     * 161-track library, which reads as a set of albums rather than a scatter of fragments.
     */
    fun minPointsFor(librarySize: Int): Int =
        kotlin.math.round(kotlin.math.ln(librarySize.toDouble().coerceAtLeast(1.0))).toInt()
            .coerceIn(MIN_ALBUM_SIZE, MAX_MIN_POINTS)

    private fun clusterSubset(
        vectors: List<FloatArray>,
        members: IntArray,
        cap: Int,
        minPoints: Int,
        epsScale: Float,
        depth: Int
    ): List<IntArray> {
        if (members.size < minPoints * 2) return emptyList()

        // Re-reduced for every subset: when a blob is split, what separates it is its own internal
        // variation, not the directions that separated it from the rest of the library.
        val reduced = reduce(members.map { vectors[it] })
        val quantiles = distanceQuantiles(reduced)
        val maxEps = quantiles.last()
        val neighbours = sortedNeighbours(reduced, maxEps)

        // The objective peaks in a narrow band just before groups merge past the cap — on the
        // real library between eps 0.152 and 0.181, with the best at 0.173 — so a single even grid
        // steps straight over it. Coarse first, stopping once one group has taken over (every
        // larger eps is the same blob), then fine around the best coarse value.
        var best = Sweep(IntArray(0), coverage = 0, eps = 0f)
        var bestStep = -1
        for ((step, eps) in quantiles.withIndex()) {
            val candidate = evaluate(neighbours, eps, cap, minPoints)
            if (candidate.coverage > best.coverage) {
                best = candidate
                bestStep = step
            }
            if (candidate.largest >= members.size * TAKEN_OVER) break
        }
        if (bestStep < 0) return emptyList()
        val low = quantiles[(bestStep - 1).coerceAtLeast(0)]
        val high = quantiles[(bestStep + 1).coerceAtMost(quantiles.lastIndex)]
        for (i in 1 until REFINE_STEPS) {
            val candidate = evaluate(neighbours, low + (high - low) * i / REFINE_STEPS, cap, minPoints)
            if (candidate.coverage > best.coverage) best = candidate
        }
        val labels = if (epsScale == 1f) {
            best.labels
        } else {
            val eps = best.eps * epsScale
            // The sweep only gathered neighbours out to its own largest candidate.
            dbscan(if (eps <= maxEps) neighbours else sortedNeighbours(reduced, eps), eps, minPoints)
        }

        val albums = ArrayList<IntArray>()
        for (group in groups(labels)) {
            val global = IntArray(group.size) { members[group[it]] }
            when {
                group.size < minPoints -> Unit
                group.size <= cap -> albums += global
                depth < MAX_DEPTH -> albums += clusterSubset(vectors, global, cap, minPoints, epsScale = 1f, depth = depth + 1)
                // Could not be split into albums: better left out than shown as a 200-track "album".
                else -> Unit
            }
        }
        return albums
    }

    // ------------------------------------------------------------------ reduction

    /**
     * Centres the vectors, projects them onto the principal components carrying [VARIANCE_TO_KEEP]
     * of the variance, and renormalises so cosine distance applies again.
     */
    internal fun reduce(vectors: List<FloatArray>): List<FloatArray> {
        val d = vectors[0].size
        val mean = FloatArray(d)
        for (v in vectors) for (i in 0 until d) mean[i] += v[i]
        for (i in 0 until d) mean[i] /= vectors.size
        val centred = vectors.map { v -> FloatArray(d) { v[it] - mean[it] } }

        val pca = principalComponents(centred, min(MAX_DIMENSIONS, d))
        val k = dimensionsToKeep(pca.eigenvalues, pca.totalVariance)

        return centred.map { x ->
            val y = FloatArray(k) { c -> dot(pca.components[c], x) }
            normalize(y)
        }
    }

    internal class Pca(val components: Array<FloatArray>, val eigenvalues: FloatArray, val totalVariance: Float)

    /**
     * Top [count] principal components of already-centred data, by subspace iteration on the d×d
     * covariance. d is 512 for CLAP, so the covariance is a quarter of a million floats and the
     * iteration is cheap; the n·d² accumulation is the dominant cost.
     */
    internal fun principalComponents(centred: List<FloatArray>, count: Int, iterations: Int = 60): Pca {
        val d = centred[0].size
        val covariance = FloatArray(d * d)
        for (x in centred) {
            for (a in 0 until d) {
                val xa = x[a]
                if (xa == 0f) continue
                val row = a * d
                for (b in a until d) covariance[row + b] += xa * x[b]
            }
        }
        for (a in 0 until d) for (b in a + 1 until d) covariance[b * d + a] = covariance[a * d + b]
        var trace = 0f
        for (a in 0 until d) trace += covariance[a * d + a]

        // Deterministic start so the same library always yields the same albums.
        val random = Random(1234)
        var basis = Array(count) { FloatArray(d) { random.nextFloat() - 0.5f } }
        orthonormalize(basis)
        repeat(iterations) {
            basis = Array(count) { c -> multiply(covariance, d, basis[c]) }
            orthonormalize(basis)
        }
        val eigenvalues = FloatArray(count) { c -> dot(basis[c], multiply(covariance, d, basis[c])) }

        // Subspace iteration converges on the subspace, not necessarily in eigenvalue order.
        val order = eigenvalues.indices.sortedByDescending { eigenvalues[it] }
        return Pca(
            components = Array(count) { basis[order[it]] },
            eigenvalues = FloatArray(count) { eigenvalues[order[it]] },
            totalVariance = trace
        )
    }

    internal fun dimensionsToKeep(eigenvalues: FloatArray, total: Float): Int {
        if (total <= 0f) return MIN_DIMENSIONS.coerceAtMost(eigenvalues.size)
        var cumulative = 0f
        for (i in eigenvalues.indices) {
            cumulative += eigenvalues[i]
            if (cumulative >= VARIANCE_TO_KEEP * total) return (i + 1).coerceIn(MIN_DIMENSIONS, eigenvalues.size)
        }
        return eigenvalues.size
    }

    private fun multiply(matrix: FloatArray, d: Int, v: FloatArray): FloatArray {
        val out = FloatArray(d)
        for (a in 0 until d) {
            var sum = 0f
            val row = a * d
            for (b in 0 until d) sum += matrix[row + b] * v[b]
            out[a] = sum
        }
        return out
    }

    /** Modified Gram-Schmidt, in place. */
    private fun orthonormalize(basis: Array<FloatArray>) {
        for (i in basis.indices) {
            for (j in 0 until i) {
                val projection = dot(basis[i], basis[j])
                for (t in basis[i].indices) basis[i][t] -= projection * basis[j][t]
            }
            val norm = sqrt(dot(basis[i], basis[i]))
            if (norm > 1e-12f) for (t in basis[i].indices) basis[i][t] /= norm
        }
    }

    // ------------------------------------------------------------------ DBSCAN

    /** eps candidates: evenly spaced quantiles of the pairwise distance distribution. */
    internal fun distanceQuantiles(points: List<FloatArray>): FloatArray {
        val n = points.size
        val pairs = n.toLong() * (n - 1) / 2
        val sample: FloatArray = if (pairs <= QUANTILE_SAMPLE) {
            val all = FloatArray(pairs.toInt())
            var k = 0
            for (i in 0 until n) for (j in i + 1 until n) all[k++] = 1f - dot(points[i], points[j])
            all
        } else {
            val random = Random(99)
            FloatArray(QUANTILE_SAMPLE) {
                var i: Int
                var j: Int
                do {
                    i = random.nextInt(n)
                    j = random.nextInt(n)
                } while (i == j)
                1f - dot(points[i], points[j])
            }
        }
        sample.sort()
        return FloatArray(EPS_STEPS) { step ->
            val q = LOWEST_QUANTILE + (HIGHEST_QUANTILE - LOWEST_QUANTILE) * step / (EPS_STEPS - 1)
            sample[(q * (sample.size - 1)).toInt()]
        }
    }

    /**
     * Each point's neighbours out to [maxEps], nearest first. Computed once; every eps in the sweep
     * then reads a prefix, so trying forty values of eps costs little more than trying one.
     */
    internal class Neighbours(val indices: Array<IntArray>, val distances: Array<FloatArray>) {
        /** How many of point [p]'s neighbours lie within [eps] (the point itself not included). */
        fun countWithin(p: Int, eps: Float): Int {
            val d = distances[p]
            var low = 0
            var high = d.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (d[mid] <= eps) low = mid + 1 else high = mid
            }
            return low
        }
    }

    internal fun sortedNeighbours(points: List<FloatArray>, maxEps: Float): Neighbours {
        val n = points.size
        val found = Array(n) { ArrayList<Pair<Float, Int>>() }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val distance = 1f - dot(points[i], points[j])
                if (distance <= maxEps) {
                    found[i] += distance to j
                    found[j] += distance to i
                }
            }
        }
        val indices = Array(n) { IntArray(0) }
        val distances = Array(n) { FloatArray(0) }
        for (i in 0 until n) {
            val sorted = found[i].sortedBy { it.first }
            indices[i] = IntArray(sorted.size) { sorted[it].second }
            distances[i] = FloatArray(sorted.size) { sorted[it].first }
        }
        return Neighbours(indices, distances)
    }

    /** DBSCAN over precomputed neighbours; minPoints counts the point itself, as usual. */
    internal fun dbscan(neighbours: Neighbours, eps: Float, minPoints: Int = MIN_ALBUM_SIZE): IntArray {
        val n = neighbours.indices.size
        val unvisited = -2
        val labels = IntArray(n) { unvisited }
        var next = 0
        fun isCore(p: Int) = neighbours.countWithin(p, eps) + 1 >= minPoints

        for (p in 0 until n) {
            if (labels[p] != unvisited) continue
            if (!isCore(p)) {
                labels[p] = NOISE
                continue
            }
            val cluster = next++
            labels[p] = cluster
            val queue = ArrayDeque<Int>()
            for (k in 0 until neighbours.countWithin(p, eps)) queue.addLast(neighbours.indices[p][k])
            while (queue.isNotEmpty()) {
                val q = queue.removeFirst()
                if (labels[q] == NOISE) labels[q] = cluster
                if (labels[q] != unvisited) continue
                labels[q] = cluster
                if (isCore(q)) {
                    for (k in 0 until neighbours.countWithin(q, eps)) {
                        val r = neighbours.indices[q][k]
                        if (labels[r] == unvisited || labels[r] == NOISE) queue.addLast(r)
                    }
                }
            }
        }
        return labels
    }

    private class Sweep(val labels: IntArray, val coverage: Int, val eps: Float, val largest: Int = 0)

    /** Runs DBSCAN at [eps] and scores it: tracks placed in groups of album size. */
    private fun evaluate(neighbours: Neighbours, eps: Float, cap: Int, minPoints: Int): Sweep {
        val labels = dbscan(neighbours, eps, minPoints)
        val sizes = clusterSizes(labels)
        return Sweep(
            labels = labels,
            coverage = sizes.filter { it in minPoints..cap }.sum(),
            eps = eps,
            largest = sizes.maxOrNull() ?: 0
        )
    }

    private fun clusterSizes(labels: IntArray): Collection<Int> =
        labels.filter { it >= 0 }.groupingBy { it }.eachCount().values

    private fun groups(labels: IntArray): List<IntArray> =
        labels.indices.filter { labels[it] >= 0 }.groupBy { labels[it] }.values.map { it.toIntArray() }

    // ------------------------------------------------------------------ vectors

    internal fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until min(a.size, b.size)) sum += a[i] * b[i]
        return sum
    }

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(dot(v, v))
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    /** The mean direction of an album, used to name it. */
    fun centroid(vectors: List<FloatArray>, members: IntArray): FloatArray {
        val d = vectors[members[0]].size
        val sum = FloatArray(d)
        for (m in members) for (i in 0 until d) sum[i] += vectors[m][i]
        return normalize(sum)
    }
}
