package music.ai.recommend.platform

import java.io.InputStream

/**
 * A file that travels inside the jar rather than being downloaded: the tokenizer vocabularies.
 *
 * Here rather than inside one encoder because there are two of them now — CLAP's byte-level BPE
 * reads vocab.json and merges.txt, the Russian student's WordPiece reads ru_vocab.txt — and the
 * list of places to look is the kind of thing that must not exist twice.
 */
internal object BundledResource {

    /**
     * @return the file as a stream, or null when it is not in the jar at all.
     *
     * Compose Resources lays files out under the package the build pins in
     * `compose.resources.packageOfResClass`, precisely so that this string does not depend on the
     * module's name. Nothing checks it: if the two ever disagree, a vocabulary is simply not found,
     * and only a text search would notice. The other three spellings are what a run from Gradle,
     * an older plugin version and a plain classpath put it under.
     */
    fun open(name: String): InputStream? {
        val paths = listOf(
            "composeResources/music.ai.recommend.resources/files/$name",
            "composeResources/files/$name",
            "files/$name",
            name
        )
        for (path in paths) {
            val stream = javaClass.classLoader.getResourceAsStream(path)
                ?: javaClass.getResourceAsStream("/$path")
            if (stream != null) return stream
        }
        return null
    }
}

/**
 * A vector of unit length, in place.
 *
 * Both text encoders need it and neither graph promises it: CLAP's normalises already and the
 * student's does too, but "already normalised" is a property of an export, not of a contract, and a
 * cosine against a vector of unknown length means nothing in particular.
 */
internal fun normalizeUnit(v: FloatArray): FloatArray {
    var norm = 0f
    for (x in v) norm += x * x
    norm = kotlin.math.sqrt(norm)
    if (norm > 0f) {
        for (i in v.indices) v[i] /= norm
    }
    return v
}
