package music.ai.recommend.ai

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shared flac decoder against ffmpeg's, on whatever real music this machine has.
 *
 * Flac is lossless, so this is not a test of "close enough": two correct decoders produce the same
 * integers, and a difference anywhere means one of them is wrong. Which makes ffmpeg an oracle
 * rather than a second opinion — and the only oracle worth having for a decoder written by hand.
 *
 * What is compared is the excerpt the analysis actually uses: ffmpeg decodes the whole file at its
 * own rate, and this test then does to its output exactly what [FlacExcerpt] does to its own —
 * mixes the channels down, cuts the same window, resamples with [Excerpt.resample] — so a
 * difference can only come from the decoding.
 *
 * It skips when there is no music folder or no ffmpeg, because it is a check on this machine's own
 * library rather than a fixture. The committed fixture test is [FlacExcerptTest]; this is the one
 * that says the decoder survives four hundred files somebody actually owns.
 */
class FlacAgainstFfmpegTest {

    private val library = File(System.getProperty("user.home"), "Music")

    /** How many files to compare. All of them takes minutes; the first few dozen find most bugs. */
    private val limit = System.getProperty("flac.limit")?.toIntOrNull() ?: 40

    @Test
    fun theSharedDecoderAgreesWithFfmpegSampleForSample() {
        if (!library.isDirectory) {
            println("FlacAgainstFfmpeg: no ${library.path}, skipped")
            return
        }
        if (!hasFfmpeg()) {
            println("FlacAgainstFfmpeg: no ffmpeg, skipped")
            return
        }

        val files = library.walkTopDown()
            .filter { it.isFile && FlacExcerpt.handles(it.name) }
            .sortedBy { it.path }
            .take(limit)
            .toList()
        if (files.isEmpty()) {
            println("FlacAgainstFfmpeg: no flac files under ${library.path}, skipped")
            return
        }

        var checked = 0
        var identical = 0
        val complaints = ArrayList<String>()
        for (file in files) {
            val mine = FlacExcerpt.decode(file)
            val theirs = throughFfmpeg(file)
            if (theirs == null) {
                complaints.add("${file.name}: ffmpeg would not decode it")
                continue
            }
            checked++
            if (mine.size != theirs.size) {
                complaints.add("${file.name}: ${mine.size} samples against ${theirs.size}")
                continue
            }
            var worst = 0f
            var at = -1
            for (index in mine.indices) {
                val difference = abs(mine[index] - theirs[index])
                if (difference > worst) {
                    worst = difference
                    at = index
                }
            }
            if (worst == 0f) identical++ else complaints.add("${file.name}: worst $worst at $at")
        }

        println("FlacAgainstFfmpeg: $identical of $checked files identical sample for sample")
        complaints.take(10).forEach { println("  $it") }
        assertTrue(checked > 0, "nothing was compared")
        assertTrue(complaints.isEmpty(), "${complaints.size} files differ; first: ${complaints.firstOrNull()}")
        assertTrue(identical == checked, "$identical of $checked identical")
    }

    private fun hasFfmpeg(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start().waitFor() == 0
    }.getOrDefault(false)

    /**
     * ffmpeg's decode of the same excerpt, put through the same mixing and resampling.
     *
     * The whole file at its own rate and in floats: no `-ar`, so ffmpeg's resampler never runs, and
     * no `-ac`, because its stereo mix preserves energy where this app averages. Everything after
     * the decode is this app's own code, on both sides of the comparison.
     */
    private fun throughFfmpeg(file: File): FloatArray? {
        val rate = probe(file, "sample_rate")?.toIntOrNull() ?: return null
        val channels = probe(file, "channels")?.toIntOrNull() ?: return null
        val start = FlacExcerpt.startMs(file) ?: return null

        val process = ProcessBuilder(
            "ffmpeg", "-i", file.path, "-f", "f32le", "-loglevel", "error", "pipe:1"
        ).start()
        val bytes = process.inputStream.use { it.readBytes() }
        process.errorStream.use { it.readBytes() }
        if (process.waitFor() != 0) return null

        val total = bytes.size / 4 / channels
        val startSample = (start * rate / 1000).toInt()
        val needed = Excerpt.sourceSamplesNeeded(rate)
        if (startSample >= total) return null
        val mono = FloatArray(minOf(needed, total - startSample))
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (index in mono.indices) {
            var sum = 0f
            val at = (startSample + index) * channels
            for (channel in 0 until channels) sum += buffer.get(at + channel)
            mono[index] = sum / channels
        }
        return Excerpt.resample(mono, rate)
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
