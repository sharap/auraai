package music.ai.recommend.ai

import java.io.File
import java.io.RandomAccessFile

/**
 * Decodes a wav excerpt, which is the easy end of the same problem [FlacExcerpt] solves.
 *
 * There is nothing to decode — the samples are the file — so any two readers agree about them by
 * definition. What they do not agree about without being told is everything around the samples:
 * where the window starts, how the channels are mixed down, and which resampler runs. So this
 * exists for the same reason as the other two: both devices do this arithmetic rather than two
 * different reasonable things, and a vector made from a wav can therefore travel.
 *
 * It also happens to be the one format where the length is beyond argument — bytes of audio
 * divided by bytes of a frame — which is what makes the excerpt agree on both sides.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object WavExcerpt {

    /** See [FlacExcerpt.DECODER]: the name travels with a vector, so a change to it is a new name. */
    const val DECODER = "wav-aura-1"

    fun handles(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("wav", "wave")

    /** Where the excerpt begins, from the size of the audio itself, or null for a file it cannot read. */
    fun startMs(file: File): Long? {
        val wav = runCatching { RandomAccessFile(file, "r").use { shape(it) } }.getOrNull() ?: return null
        if (wav.frames <= 0L || wav.sampleRate <= 0) return null
        return Excerpt.startMs(wav.durationMs)
    }

    /**
     * @return exactly [Excerpt.SAMPLES] mono samples at [Excerpt.SAMPLE_RATE], or fewer if the
     *   track is genuinely shorter than the excerpt, or an empty array if it will not read.
     */
    fun decode(file: File): FloatArray = try {
        RandomAccessFile(file, "r").use { raf ->
            val wav = shape(raf) ?: return FloatArray(0)
            if (wav.frames <= 0L || wav.sampleRate <= 0 || wav.channels <= 0) return FloatArray(0)

            val startSample = Excerpt.startMs(wav.durationMs) * wav.sampleRate / 1000
            if (startSample >= wav.frames) return FloatArray(0)
            val needed = Excerpt.sourceSamplesNeeded(wav.sampleRate)
            val available = (wav.frames - startSample).coerceAtMost(needed.toLong()).toInt()

            val frameBytes = wav.channels * wav.bytesPerSample
            raf.seek(wav.dataAt + startSample * frameBytes)
            val raw = ByteArray(available * frameBytes)
            var filled = 0
            while (filled < raw.size) {
                val read = raf.read(raw, filled, raw.size - filled)
                if (read <= 0) break
                filled += read
            }
            // A file that stops early — truncated, or still being copied — gives what it has.
            val frames = filled / frameBytes
            val mono = FloatArray(frames)
            for (index in 0 until frames) {
                var sum = 0f
                val at = index * frameBytes
                for (channel in 0 until wav.channels) {
                    sum += sample(raw, at + channel * wav.bytesPerSample, wav)
                }
                mono[index] = sum / wav.channels
            }
            Excerpt.resample(mono, wav.sampleRate)
        }
    } catch (e: Exception) {
        FloatArray(0)
    }

    private class Wav(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val float: Boolean,
        val dataAt: Long,
        val dataBytes: Long
    ) {
        val bytesPerSample: Int get() = bitsPerSample / 8
        val frames: Long get() = if (channels == 0 || bytesPerSample == 0) 0 else dataBytes / (channels * bytesPerSample)
        val durationMs: Long get() = frames * 1000L / sampleRate
    }

    /** @return what the file says it is, or null when it is not a wav this can read. */
    private fun shape(raf: RandomAccessFile): Wav? {
        val riff = ByteArray(12)
        if (raf.read(riff) != 12) return null
        if (text(riff, 0) != "RIFF" || text(riff, 8) != "WAVE") return null

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var float = false
        val length = raf.length()

        while (raf.filePointer + 8 <= length) {
            val head = ByteArray(8)
            if (raf.read(head) != 8) return null
            val id = text(head, 0)
            val size = little(head, 4, 4)
            val body = raf.filePointer
            when (id) {
                "fmt " -> {
                    if (size < 16) return null
                    val fmt = ByteArray(minOf(size, 40L).toInt())
                    raf.readFully(fmt)
                    var format = little(fmt, 0, 2).toInt()
                    channels = little(fmt, 2, 2).toInt()
                    sampleRate = little(fmt, 4, 4).toInt()
                    bits = little(fmt, 14, 2).toInt()
                    // The extensible form says what it really is in the first two bytes of a guid
                    // at the end of the block, which is the same two numbers as a plain header's.
                    if (format == EXTENSIBLE && fmt.size >= 26) format = little(fmt, 24, 2).toInt()
                    float = format == FORMAT_FLOAT
                    if (format != FORMAT_PCM && format != FORMAT_FLOAT) return null
                }
                "data" -> {
                    if (sampleRate <= 0 || channels <= 0 || bits <= 0) return null
                    if (bits % 8 != 0 || bits > 64) return null
                    if (float && bits != 32 && bits != 64) return null
                    // The size in the header is trusted only as far as the file goes: a recording
                    // that was interrupted says more than it holds.
                    val holds = (length - body).coerceAtLeast(0)
                    return Wav(sampleRate, channels, bits, float, body, minOf(size, holds))
                }
            }
            // Chunks are padded to an even length, and a reader that forgets that reads the pad
            // byte as the start of the next chunk's name.
            raf.seek(body + size + (size and 1L))
        }
        return null
    }

    /** One sample, scaled to ±1 the way every other decoder here scales it. */
    private fun sample(raw: ByteArray, at: Int, wav: Wav): Float = when {
        wav.float && wav.bitsPerSample == 32 ->
            Float.fromBits(little(raw, at, 4).toInt())
        wav.float ->
            Double.fromBits(little(raw, at, 8)).toFloat()
        wav.bitsPerSample == 8 ->
            // Eight-bit wav is unsigned, with silence at 128 rather than at zero.
            ((raw[at].toInt() and 0xFF) - 128) / 128f
        else -> {
            val bits = wav.bitsPerSample
            val value = little(raw, at, bits / 8)
            val sign = 1L shl (bits - 1)
            val signed = if (value and sign != 0L) value - (1L shl bits) else value
            signed.toFloat() / sign.toFloat()
        }
    }

    private fun text(bytes: ByteArray, at: Int): String =
        String(bytes, at, 4, Charsets.US_ASCII)

    private fun little(bytes: ByteArray, at: Int, count: Int): Long {
        var value = 0L
        for (index in 0 until count) {
            value = value or ((bytes[at + index].toLong() and 0xFFL) shl (8 * index))
        }
        return value
    }

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val EXTENSIBLE = 0xFFFE
}
