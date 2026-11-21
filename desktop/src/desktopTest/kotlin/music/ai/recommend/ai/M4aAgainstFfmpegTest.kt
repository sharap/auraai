package music.ai.recommend.ai

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shared m4a decoder against ffmpeg's, on whatever real music this machine has.
 *
 * Unlike flac, this cannot be a test of equality and must not pretend to be: AAC is lossy and its
 * specification defines how close a decoder has to be rather than what it has to output, so two
 * correct decoders disagree on purpose. What can be checked is the thing that actually goes wrong
 * — alignment. A window that starts in the wrong place, or a priming that was not skipped, leaves
 * two signals that correlate at nothing in particular; the right window leaves two signals that
 * are the same music to three or four decimal places.
 *
 * So the measure is the cosine between the two excerpts, which is also the measure the embeddings
 * are judged by, and the error relative to the signal's own size. It skips when there is no music
 * folder or no ffmpeg.
 */
class M4aAgainstFfmpegTest {

    private val library = File(System.getProperty("user.home"), "Music")
    private val limit = System.getProperty("m4a.limit")?.toIntOrNull() ?: 140

    @Test
    fun theSharedDecoderHearsTheSameMusicAsFfmpeg() {
        if (!library.isDirectory) {
            println("M4aAgainstFfmpeg: no ${library.path}, skipped")
            return
        }
        if (!hasFfmpeg()) {
            println("M4aAgainstFfmpeg: no ffmpeg, skipped")
            return
        }
        val files = library.walkTopDown()
            .filter { it.isFile && M4aExcerpt.handles(it.name) }
            .sortedBy { it.path }
            .take(limit)
            .toList()
        if (files.isEmpty()) {
            println("M4aAgainstFfmpeg: no m4a files, skipped")
            return
        }

        var checked = 0
        val cosines = ArrayList<Double>()
        var worstCosine = 1.0
        var worstName = ""
        val complaints = ArrayList<String>()
        for (file in files) {
            val mine = M4aExcerpt.decode(file)
            if (mine.isEmpty()) {
                complaints.add("${file.name}: the shared decoder gave nothing")
                continue
            }
            val theirs = throughFfmpeg(file, mine.size)
            if (theirs == null || theirs.size != mine.size) {
                complaints.add("${file.name}: ffmpeg gave ${theirs?.size} against ${mine.size}")
                continue
            }
            checked++
            val cosine = cosineOf(mine, theirs)
            val error = sqrt(mine.indices.sumOf { ((mine[it] - theirs[it]).toDouble()).let { d -> d * d } } / mine.size)
            val signal = sqrt(mine.sumOf { (it.toDouble()) * it } / mine.size)
            if (cosine < worstCosine) {
                worstCosine = cosine
                worstName = file.name
            }
            cosines.add(cosine)
            if (cosine < FLOOR) {
                complaints.add("${file.name}: cosine $cosine, error $error against signal $signal")
            }
        }

        val mean = if (cosines.isEmpty()) 0.0 else cosines.average()
        val below = cosines.count { it < 0.9999 }
        println(
            "M4aAgainstFfmpeg: $checked files, mean cosine $mean, worst $worstCosine on " +
                "$worstName, $below below 0.9999"
        )
        complaints.take(10).forEach { println("  $it") }
        assertTrue(checked > 0, "nothing was compared")
        assertTrue(complaints.isEmpty(), "${complaints.size} files out of step; first: ${complaints.firstOrNull()}")
        // Two decoders of a lossy format, listening to the same ten seconds. Where the floor is
        // matters, so here is what it is for: AAC's specification says how close a decoder must
        // be, not what it must output, so these two differ on purpose and the difference is
        // uneven — most files agree to five decimal places and a few to three. What the floor
        // catches is the failure that looks like this and is not: a window in the wrong place, a
        // priming not skipped, a dropped frame. Those land far below it. Measured over the 133
        // m4a files this was written for, the mean was 0.9999 and the worst 0.9982.
        assertTrue(worstCosine > FLOOR, "worst cosine $worstCosine on $worstName")
        assertTrue(mean > 0.999, "mean cosine $mean")
    }

    private companion object {
        /** Below this, two decoders are not listening to the same audio. */
        const val FLOOR = 0.99
    }

    private fun cosineOf(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var left = 0.0
        var right = 0.0
        for (index in a.indices) {
            dot += a[index].toDouble() * b[index]
            left += a[index].toDouble() * a[index]
            right += b[index].toDouble() * b[index]
        }
        return if (left == 0.0 || right == 0.0) 0.0 else dot / sqrt(left * right)
    }

    private fun hasFfmpeg(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start().waitFor() == 0
    }.getOrDefault(false)

    /**
     * ffmpeg's decode of the same window, put through the same mixing and resampling.
     *
     * The whole file at its own rate and in floats, with the window cut here — so the comparison
     * is of the decoding and not of two different ideas about where the middle of a track is.
     * ffmpeg skips the encoder's priming itself, which is what makes the two line up at all.
     */
    private fun throughFfmpeg(file: File, wanted: Int): FloatArray? {
        val rate = probe(file, "sample_rate")?.toIntOrNull() ?: return null
        val channels = probe(file, "channels")?.toIntOrNull() ?: return null
        val start = M4aExcerpt.startMs(file) ?: return null

        val process = ProcessBuilder(
            "ffmpeg", "-i", file.path, "-f", "f32le", "-loglevel", "error", "pipe:1"
        ).start()
        val drain = Thread { runCatching { process.errorStream.use { it.readBytes() } } }
            .apply { isDaemon = true; start() }
        try {
            // Streamed and not read whole: a three-minute file decodes to sixty megabytes, and a
            // hundred of them at once is how this test first met the garbage collector. The window
            // is a couple of megabytes and nothing else is kept.
            val frameBytes = channels * 4
            val startSample = start * rate / 1000
            val needed = Excerpt.sourceSamplesNeeded(rate)
            val input = java.io.BufferedInputStream(process.inputStream, 1 shl 16)
            var skipped = 0L
            val toSkip = startSample * frameBytes
            while (skipped < toSkip) {
                val jumped = input.skip(toSkip - skipped)
                if (jumped <= 0) return null
                skipped += jumped
            }
            val raw = ByteArray(needed * frameBytes)
            var filled = 0
            while (filled < raw.size) {
                val read = input.read(raw, filled, raw.size - filled)
                if (read <= 0) break
                filled += read
            }
            val frames = filled / frameBytes
            if (frames == 0) return null
            val floats = java.nio.ByteBuffer.wrap(raw, 0, frames * frameBytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val mono = FloatArray(frames)
            for (index in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) sum += floats.get(index * channels + channel)
                mono[index] = sum / channels
            }
            val resampled = Excerpt.resample(mono, rate)
            return if (resampled.size >= wanted) resampled.copyOf(wanted) else resampled
        } finally {
            process.destroy()
            drain.join(1_000)
        }
    }

    private fun probe(file: File, what: String): String? = runCatching {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=$what", "-of", "default=nw=1:nk=1", file.path
        ).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.errorStream.use { it.readBytes() }
        if (process.waitFor() == 0 && text.isNotEmpty()) text.lineSequence().first() else null
    }.getOrNull()
}
