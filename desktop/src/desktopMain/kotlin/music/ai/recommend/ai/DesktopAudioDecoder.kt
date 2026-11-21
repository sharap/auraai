package music.ai.recommend.ai

import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Decodes an excerpt of a track to mono 48 kHz float PCM by piping it through ffmpeg.
 *
 * @throws DecodeUnavailable when ffmpeg itself cannot be run, which is a problem with the
 *   installation rather than with the track and must not be mistaken for an undecodable file.
 */
class DesktopAudioDecoder {

    /** ffmpeg is missing from PATH, so no track can be decoded. */
    class DecodeUnavailable(message: String) : Exception(message)

    /**
     * Whether ffmpeg can be run at all, probed once.
     *
     * Without this a missing ffmpeg looked exactly like a library of undecodable files: every
     * track logged one line and was skipped, and the scan "finished" having stored nothing.
     */
    val isAvailable: Boolean by lazy {
        try {
            val probe = ProcessBuilder("ffmpeg", "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                probe.destroyForcibly()
                false
            } else {
                probe.exitValue() == 0
            }
        } catch (e: Exception) {
            println("AudioDecoder: ffmpeg is not available: ${e.message}")
            false
        }
    }

    /**
     * Decodes the excerpt [Excerpt] picks, as exactly [Excerpt.SAMPLES] samples.
     *
     * The exact count matters more than it looks. One sample short of ten seconds and the feature
     * extractor takes its repeat-and-pad branch — the one meant for tracks shorter than the
     * excerpt — and finishes the excerpt with silence; measured on one mp3 that moved the
     * embedding by a cosine of 0.10, against the 0.9985 at which two embeddings count as the same.
     * So ffmpeg is asked for a little more than ten seconds and exactly ten are read back, which
     * leaves a shortfall meaning what it should: the track really is that short.
     *
     * @param durationMs how much audio to return, normally [Excerpt.LENGTH_MS].
     * @param trackDurationMs the track's full length, so the excerpt can be taken from the middle;
     *   0 when the scanner could not read it, in which case ffprobe is asked.
     * @return mono 48 kHz samples, or an empty array if the track could not be decoded.
     */
    fun decodeChunk(path: String, durationMs: Long, trackDurationMs: Long = 0L): FloatArray {
        if (!isAvailable) throw DecodeUnavailable("ffmpeg not found in PATH")

        val targetSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val decodedSamples = FloatArray(targetSamples)

        var process: Process? = null
        try {
            // The middle of the track, the way the reference scanner picks it. Seeking to a fixed
            // 20 s instead meant every embedding described whatever happened to be playing there —
            // usually still the intro — and anything shorter than 20 s decoded to nothing at all.
            val startSeconds = startOffsetSeconds(path, trackDurationMs)
            val channels = probeChannels(path)

            // Asked for longer than wanted, because exactly how many samples ffmpeg hands over for
            // a request of exactly ten seconds depends on how it rounds the end of the span. The
            // read below stops at the sample that is wanted, so the surplus costs a fraction of a
            // second of decoding and buys an excerpt that is never a sample short.
            val builder = ProcessBuilder(
                "ffmpeg",
                "-ss", formatSeconds(startSeconds),
                "-i", path,
                "-t", formatSeconds(durationMs / 1000.0 + SPAN_SLACK_SECONDS),
                // Channels are left alone and mixed below, and samples come over as floats.
                // Asking ffmpeg for mono was wrong twice over: its stereo mix preserves energy,
                // putting out 1/sqrt(2) per channel where the Android app averages, so this side
                // ran 3 dB hot, and 16-bit output then clipped the result — peaks of 1.49 arrived
                // as 1.00. Both showed up as a difference in the embedding, because the features
                // are logarithmic and the model is not deaf to level.
                "-f", "f32le",
                "-ar", SAMPLE_RATE.toString(),
                "-loglevel", "error",
                "pipe:1"
            )
            process = builder.start()

            // Drained on its own thread: ffmpeg blocks once the stderr pipe fills, and with the
            // reader on this thread that is a deadlock against the stdout read below.
            val errors = StringBuilder()
            val stderrPump = Thread {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        synchronized(errors) { if (errors.length < MAX_ERROR_CHARS) errors.append(line).append('\n') }
                    }
                }
            }.apply { isDaemon = true; start() }

            // Read by offset into one buffer of exactly the size wanted. The previous reader
            // stepped through each chunk on its own and had to carry a half-read sample over to
            // the next one, which is a whole class of bug that this does not have.
            val bytesWanted = targetSamples * channels * BYTES_PER_SAMPLE
            val raw = ByteArray(bytesWanted)
            var filled = 0
            BufferedInputStream(process.inputStream).use { input ->
                while (filled < bytesWanted) {
                    val read = input.read(raw, filled, bytesWanted - filled)
                    if (read < 0) break
                    filled += read
                }
            }

            // The channel mix the Android app does, so that both sides hand the model the same
            // signal: the plain average, in floating point, which cannot overflow.
            var samplesRead = filled / BYTES_PER_SAMPLE / channels
            val samples = ByteBuffer.wrap(raw, 0, filled).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            for (frame in 0 until samplesRead) {
                var sum = 0f
                for (channel in 0 until channels) sum += samples.get(frame * channels + channel)
                decodedSamples[frame] = sum / channels
            }

            if (!process.waitFor(DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                println("AudioDecoder: ffmpeg timed out on $path")
            }
            stderrPump.join(500)

            if (samplesRead == 0) {
                val reason = synchronized(errors) { errors.toString().trim() }
                println("AudioDecoder: no audio decoded from $path${if (reason.isEmpty()) "" else ": $reason"}")
                return FloatArray(0)
            }

            return if (samplesRead == targetSamples) decodedSamples else decodedSamples.copyOf(samplesRead)
        } catch (e: DecodeUnavailable) {
            throw e
        } catch (e: Exception) {
            println("AudioDecoder: ffmpeg error on $path: ${e.message}")
            return FloatArray(0)
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }

    /**
     * Where the excerpt starts, by the rule both platforms follow: see [Excerpt.startMs].
     *
     * The length comes from the tag reader when it could read one, and that is in whole seconds,
     * which is half the reason the rule rounds the way it does — Android has the duration to the
     * microsecond and would otherwise centre the window somewhere else entirely.
     */
    private fun startOffsetSeconds(path: String, trackDurationMs: Long): Double {
        val lengthMs = if (trackDurationMs > 0) trackDurationMs else probeDurationMs(path)
        return Excerpt.startMs(lengthMs) / 1000.0
    }

    /** Track length from ffprobe, for the files the library scanner could not read a header from. */
    private fun probeDurationMs(path: String): Long = try {
        val builder = ProcessBuilder(
            "ffprobe",
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            path
        ).redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        ((text.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    } catch (e: Exception) {
        0L
    }

    /** How many channels the audio stream carries, so they can be mixed down here. */
    private fun probeChannels(path: String): Int = try {
        val process = ProcessBuilder(
            "ffprobe",
            "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=channels",
            "-of", "default=noprint_wrappers=1:nokey=1",
            path
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        (text.toIntOrNull() ?: 1).coerceIn(1, MAX_CHANNELS)
    } catch (e: Exception) {
        1
    }

    /** ffmpeg parses times in the C locale, so the decimal separator must not follow the user's. */
    private fun formatSeconds(seconds: Double): String =
        String.format(java.util.Locale.ROOT, "%.3f", seconds)

    private companion object {
        const val SAMPLE_RATE = Excerpt.SAMPLE_RATE

        /** How much longer than the excerpt ffmpeg is asked for, so the last sample is never cut. */
        const val SPAN_SLACK_SECONDS = 0.1
        const val BYTES_PER_SAMPLE = 4

        /** A sanity bound on what ffprobe reports, so a nonsense answer cannot size a buffer. */
        const val MAX_CHANNELS = 8
        const val MAX_ERROR_CHARS = 2000
        const val PROBE_TIMEOUT_SECONDS = 10L

        /** Upper bound on one track's decode, so a stuck ffmpeg cannot hang the scan. */
        const val DECODE_TIMEOUT_SECONDS = 30L
    }
}
