package music.ai.recommend.ai

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Decodes a flac excerpt with a decoder that is the same code everywhere, so that two devices
 * analysing the same file hand the model the same samples.
 *
 * Flac is lossless, which sounds as though any two decoders would agree and the platform's would
 * do. They do agree about the samples — and still cannot be used, for two reasons that have
 * nothing to do with the codec. The desktop decodes by piping the file through ffmpeg and asks it
 * for 48 kHz, so ffmpeg's polyphase resampler runs; the phone decodes at the file's own rate and
 * resamples with [Excerpt.resample], which is linear. Those are different numbers, and the model
 * charges about 0.002 of cosine for the difference where 0.9985 is the bar. And the start of the
 * window is given to ffmpeg in seconds, so it begins wherever ffmpeg's seek lands rather than at
 * the sample asked for.
 *
 * Owning the decoder settles both, and for flac it also settles the thing that is hardest to agree
 * on by accident: how long the track is. The sample count is written in the stream's header, exact
 * and in samples, so both devices work out the same middle instead of one reading milliseconds from
 * a container and the other whole seconds from a tag.
 *
 * Unlike layer III there is no warm-up to do: a flac frame is decoded entirely from itself, so the
 * excerpt can be entered at a frame boundary with nothing carried in. Where the file has a seek
 * table — most encoders write one — that boundary is found in it; where it has none, the frames
 * before the window are decoded and thrown away, which is the price of not being told.
 *
 * Playback is untouched. There the platform decoder is the right tool and nothing is being
 * compared.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object FlacExcerpt {

    /**
     * Written into a synced embedding so a device can tell what produced it. Not the name of a
     * library, because this is not one: it is this app's own decoder, and the number rises if ever
     * a change to it changes a sample.
     */
    const val DECODER = "flac-aura-1"

    fun handles(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() == "flac"

    /**
     * Where the excerpt begins for this file, in milliseconds, from the sample count in the
     * stream's own header — or null for a file this cannot read.
     *
     * Costs reading the first few dozen bytes. Exact, unlike every other way of asking how long a
     * track is: the header says how many samples there are, so the duration is arithmetic rather
     * than an estimate, and both devices get the same answer by doing the same sum.
     */
    fun startMs(file: File): Long? {
        val stream = runCatching { RandomAccessFile(file, "r").use { header(it) } }.getOrNull() ?: return null
        if (stream.totalSamples <= 0L || stream.sampleRate <= 0) return null
        return Excerpt.startMs(stream.durationMs)
    }

    /**
     * @return exactly [Excerpt.SAMPLES] mono samples at [Excerpt.SAMPLE_RATE], or fewer if the
     *   track is genuinely shorter than the excerpt, or an empty array if it will not decode.
     */
    fun decode(file: File): FloatArray = try {
        RandomAccessFile(file, "r").use { raf ->
            val stream = header(raf) ?: return FloatArray(0)
            if (stream.totalSamples <= 0L || stream.sampleRate <= 0) return FloatArray(0)
            if (stream.channels !in 1..MAX_CHANNELS) return FloatArray(0)

            val startSample = Excerpt.startMs(stream.durationMs) * stream.sampleRate / 1000
            val needed = Excerpt.sourceSamplesNeeded(stream.sampleRate)

            // The last seek point at or before the window, when the file has a seek table. Its
            // sample number is the first sample of the frame it points at, so entering there is
            // entering a frame boundary with a known position — which is all that is needed,
            // because nothing is carried between flac frames.
            var at = stream.firstFrameAt
            var sampleAt = 0L
            stream.seekPoints.lastOrNull { it.sample <= startSample }?.let { point ->
                at = stream.firstFrameAt + point.offset
                sampleAt = point.sample
            }
            raf.seek(at)

            val bits = Bits(BufferedInputStream(RafStream(raf), READ_BUFFER))
            val channels = Array(MAX_CHANNELS) { IntArray(stream.maxBlockSize.coerceAtLeast(MIN_BLOCK)) }
            val mono = FloatArray(needed)
            var size = 0
            val scale = (1L shl (stream.bitsPerSample - 1)).toFloat()

            try {
                while (size < needed) {
                    val frame = frame(bits, stream, channels) ?: break
                    for (index in 0 until frame.blockSize) {
                        val position = sampleAt + index
                        if (position < startSample) continue
                        if (size >= needed) break
                        var sum = 0f
                        for (channel in 0 until frame.channels) {
                            sum += channels[channel][index] / scale
                        }
                        mono[size++] = sum / frame.channels
                    }
                    sampleAt += frame.blockSize
                }
            } catch (e: EndOfStream) {
                // A file that stops in the middle of a frame — truncated, or still being copied.
                // What was decoded before it is good; the shortfall says the track is that short,
                // which the caller already knows how to read.
            }

            Excerpt.resample(if (size == mono.size) mono else mono.copyOf(size), stream.sampleRate)
        }
    } catch (e: Exception) {
        FloatArray(0)
    }

    // ----------------------------------------------------------------------------- the header

    private class SeekPoint(val sample: Long, val offset: Long)

    private class Stream(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
        val maxBlockSize: Int,
        /** Where the audio starts, past every metadata block. */
        val firstFrameAt: Long,
        val seekPoints: List<SeekPoint>
    ) {
        /**
         * The length, in milliseconds, by the count in the header.
         *
         * Truncating division, deliberately: both devices do this sum and both have to get the
         * same whole number out of it, and [Excerpt.startMs] then quantises further.
         */
        val durationMs: Long get() = totalSamples * 1000L / sampleRate
    }

    /** @return what the file says it is, or null when it is not a flac stream at all. */
    private fun header(raf: RandomAccessFile): Stream? {
        val magic = ByteArray(4)
        if (raf.read(magic) != 4) return null
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
        ) {
            return null
        }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var totalSamples = 0L
        var maxBlockSize = 0
        val points = ArrayList<SeekPoint>()

        while (true) {
            val head = raf.read()
            if (head < 0) return null
            val last = (head and 0x80) != 0
            val type = head and 0x7F
            val length = (raf.read() shl 16) or (raf.read() shl 8) or raf.read()
            if (length < 0) return null
            when (type) {
                BLOCK_STREAMINFO -> {
                    if (length < 34) return null
                    val info = ByteArray(34)
                    raf.readFully(info)
                    maxBlockSize = ((info[2].toInt() and 0xFF) shl 8) or (info[3].toInt() and 0xFF)
                    sampleRate = ((info[10].toInt() and 0xFF) shl 12) or
                        ((info[11].toInt() and 0xFF) shl 4) or
                        ((info[12].toInt() and 0xFF) ushr 4)
                    channels = ((info[12].toInt() ushr 1) and 0x07) + 1
                    bitsPerSample = (((info[12].toInt() and 0x01) shl 4) or
                        ((info[13].toInt() and 0xFF) ushr 4)) + 1
                    totalSamples = ((info[13].toLong() and 0x0FL) shl 32) or
                        ((info[14].toLong() and 0xFFL) shl 24) or
                        ((info[15].toLong() and 0xFFL) shl 16) or
                        ((info[16].toLong() and 0xFFL) shl 8) or
                        (info[17].toLong() and 0xFFL)
                    if (length > 34) raf.seek(raf.filePointer + (length - 34))
                }
                BLOCK_SEEKTABLE -> {
                    val table = ByteArray(length)
                    raf.readFully(table)
                    var at = 0
                    while (at + 18 <= table.size) {
                        val sample = readLong(table, at)
                        val offset = readLong(table, at + 8)
                        // A placeholder point says "nothing here yet" with every bit set, and a
                        // decoder that took it for a sample number would seek past the end.
                        if (sample != PLACEHOLDER) points.add(SeekPoint(sample, offset))
                        at += 18
                    }
                }
                else -> raf.seek(raf.filePointer + length)
            }
            if (last) break
        }

        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
        return Stream(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            totalSamples = totalSamples,
            maxBlockSize = maxBlockSize,
            firstFrameAt = raf.filePointer,
            seekPoints = points
        )
    }

    private fun readLong(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (bytes[at + index].toLong() and 0xFFL)
        return value
    }

    // ------------------------------------------------------------------------------ the frames

    private class DecodedFrame(val blockSize: Int, val channels: Int)

    /**
     * Decodes one frame into [into], a channel per row.
     *
     * @return what it decoded, or null at the end of the stream.
     */
    private fun frame(bits: Bits, stream: Stream, into: Array<IntArray>): DecodedFrame? {
        val sync = bits.next(14, atEndReturn = -1L)
        if (sync < 0) return null
        if (sync != SYNC) throw EndOfStream()
        bits.next(1)                                  // reserved
        bits.next(1)                                  // blocking strategy; the position is tracked outside
        val blockSizeCode = bits.next(4).toInt()
        val sampleRateCode = bits.next(4).toInt()
        val assignment = bits.next(4).toInt()
        val sampleSizeCode = bits.next(3).toInt()
        bits.next(1)                                  // reserved
        skipCodedNumber(bits)

        val blockSize = when (blockSizeCode) {
            0 -> throw EndOfStream()
            1 -> 192
            in 2..5 -> 576 shl (blockSizeCode - 2)
            6 -> bits.next(8).toInt() + 1
            7 -> bits.next(16).toInt() + 1
            else -> 256 shl (blockSizeCode - 8)
        }
        when (sampleRateCode) {
            12 -> bits.next(8)
            13, 14 -> bits.next(16)
        }
        bits.next(8)                                  // the header's own checksum

        val bitsPerSample = when (sampleSizeCode) {
            0 -> stream.bitsPerSample
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            7 -> 32
            else -> throw EndOfStream()
        }
        val channels = if (assignment < 8) assignment + 1 else 2
        if (channels > MAX_CHANNELS || blockSize > into[0].size) throw EndOfStream()

        for (channel in 0 until channels) {
            // In the three joint-stereo arrangements one of the two channels is a difference, and
            // a difference of two numbers of n bits needs n+1 to hold it.
            val extra = when {
                assignment == LEFT_SIDE && channel == 1 -> 1
                assignment == RIGHT_SIDE && channel == 0 -> 1
                assignment == MID_SIDE && channel == 1 -> 1
                else -> 0
            }
            subframe(bits, into[channel], blockSize, bitsPerSample + extra)
        }
        bits.align()
        bits.next(16)                                 // the frame's own checksum

        when (assignment) {
            LEFT_SIDE -> for (i in 0 until blockSize) into[1][i] = into[0][i] - into[1][i]
            RIGHT_SIDE -> for (i in 0 until blockSize) into[0][i] += into[1][i]
            MID_SIDE -> for (i in 0 until blockSize) {
                val side = into[1][i]
                // The stored middle lost its lowest bit to the division that made it, and the
                // difference's lowest bit is the one that says what it was.
                val mid = (into[0][i] shl 1) or (side and 1)
                into[0][i] = (mid + side) shr 1
                into[1][i] = (mid - side) shr 1
            }
        }
        return DecodedFrame(blockSize, channels)
    }

    /** The frame or sample number, which is a variable-length number this does not need. */
    private fun skipCodedNumber(bits: Bits) {
        val first = bits.next(8).toInt()
        var extra = 0
        while (extra < 6 && (first and (0x80 ushr extra)) != 0 && (first and 0x40) != 0) extra++
        // The length is written as the count of leading ones: 0xxxxxxx is one byte, 110xxxxx two,
        // 1110xxxx three, and so on up to seven.
        var ones = 0
        while (ones < 8 && (first and (0x80 ushr ones)) != 0) ones++
        val length = if (ones == 0) 1 else ones
        for (index in 1 until length) bits.next(8)
    }

    private fun subframe(bits: Bits, out: IntArray, blockSize: Int, bitsPerSample: Int) {
        bits.next(1)                                  // reserved
        val type = bits.next(6).toInt()
        val wastedFlag = bits.next(1).toInt()
        val wasted = if (wastedFlag == 1) bits.unary() + 1 else 0
        val precision = bitsPerSample - wasted
        if (precision <= 0 || precision > 32) throw EndOfStream()

        when {
            type == 0 -> {
                val value = bits.signed(precision).toInt()
                for (i in 0 until blockSize) out[i] = value
            }
            type == 1 -> {
                for (i in 0 until blockSize) out[i] = bits.signed(precision).toInt()
            }
            type in 8..12 -> fixed(bits, out, blockSize, precision, type - 8)
            type in 32..63 -> linear(bits, out, blockSize, precision, type - 31)
            else -> throw EndOfStream()
        }

        if (wasted > 0) for (i in 0 until blockSize) out[i] = out[i] shl wasted
    }

    /**
     * The four fixed predictors, which are differences of differences: order one predicts the
     * previous sample, order two the line through the previous two, and so on.
     */
    private fun fixed(bits: Bits, out: IntArray, blockSize: Int, precision: Int, order: Int) {
        for (i in 0 until order) out[i] = bits.signed(precision).toInt()
        residual(bits, out, blockSize, order)
        for (i in order until blockSize) {
            val prediction = when (order) {
                0 -> 0L
                1 -> out[i - 1].toLong()
                2 -> 2L * out[i - 1] - out[i - 2]
                3 -> 3L * out[i - 1] - 3L * out[i - 2] + out[i - 3]
                else -> 4L * out[i - 1] - 6L * out[i - 2] + 4L * out[i - 3] - out[i - 4]
            }
            out[i] = (out[i] + prediction).toInt()
        }
    }

    /** The encoder's own predictor: coefficients, a shift, and the residual it could not predict. */
    private fun linear(bits: Bits, out: IntArray, blockSize: Int, precision: Int, order: Int) {
        for (i in 0 until order) out[i] = bits.signed(precision).toInt()
        val coefficientBits = bits.next(4).toInt() + 1
        if (coefficientBits > 32) throw EndOfStream()
        val shift = bits.signed(5).toInt()
        if (shift < 0) throw EndOfStream()
        val coefficients = IntArray(order) { bits.signed(coefficientBits).toInt() }
        residual(bits, out, blockSize, order)
        for (i in order until blockSize) {
            // In Long, because twelve coefficients of twenty bits against samples of twenty-four
            // overflow an Int long before the shift brings them back down.
            var prediction = 0L
            for (j in 0 until order) prediction += coefficients[j].toLong() * out[i - 1 - j]
            out[i] = (out[i] + (prediction shr shift)).toInt()
        }
    }

    /**
     * The residual, in partitions, each with its own Rice parameter.
     *
     * Rice coding spends one bit per unit of magnitude in the high part and a fixed number in the
     * low part, which is exactly right for a signal whose predictor has already taken the
     * structure out of it. A partition that will not compress that way escapes to raw bits.
     */
    private fun residual(bits: Bits, out: IntArray, blockSize: Int, order: Int) {
        val method = bits.next(2).toInt()
        if (method > 1) throw EndOfStream()
        val parameterBits = if (method == 0) 4 else 5
        val escape = if (method == 0) 0x0F else 0x1F
        val partitionOrder = bits.next(4).toInt()
        val partitions = 1 shl partitionOrder
        if (blockSize shr partitionOrder == 0) throw EndOfStream()

        var at = order
        for (partition in 0 until partitions) {
            val count = (blockSize shr partitionOrder) - (if (partition == 0) order else 0)
            val parameter = bits.next(parameterBits).toInt()
            if (parameter == escape) {
                val raw = bits.next(5).toInt()
                for (i in 0 until count) {
                    out[at++] = if (raw == 0) 0 else bits.signed(raw).toInt()
                }
            } else {
                for (i in 0 until count) {
                    val quotient = bits.unary()
                    val remainder = bits.next(parameter)
                    val folded = (quotient.toLong() shl parameter) or remainder
                    // Negative numbers are folded in among the positives so that small magnitudes
                    // of either sign stay small: 0, -1, 1, -2, 2 and so on.
                    out[at++] = if (folded and 1L == 1L) {
                        (-((folded ushr 1) + 1)).toInt()
                    } else {
                        (folded ushr 1).toInt()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------- bit reading

    /** The stream ended, or held something that cannot be a flac frame. */
    private class EndOfStream : Exception() {
        /**
         * A control signal rather than a fault, so there is nothing worth a stack trace — and
         * filling one in is the expensive part of throwing.
         *
         * Done this way rather than with the constructor that takes `writableStackTrace`, which
         * arrived in API 24 against this app's 23. Lint caught that, which is why the build runs
         * it rather than only compiling.
         */
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * Bits, most significant first, which is how flac is written.
     *
     * Its own rather than a library's for the same reason as the rest of this file: the two apps
     * must do the same arithmetic, and sharing one small reader is how that is guaranteed rather
     * than hoped for.
     */
    private class Bits(private val input: InputStream) {

        private var held = 0L
        private var count = 0

        /**
         * @param atEndReturn what to answer when the stream ends cleanly before any of the bits
         *   asked for — used once, to tell the end of the audio from a broken frame.
         */
        fun next(bits: Int, atEndReturn: Long? = null): Long {
            while (count < bits) {
                val byte = input.read()
                if (byte < 0) {
                    if (count == 0 && atEndReturn != null) return atEndReturn
                    throw EndOfStream()
                }
                held = (held shl 8) or (byte.toLong() and 0xFFL)
                count += 8
            }
            count -= bits
            val value = (held ushr count) and mask(bits)
            held = held and mask(count)
            return value
        }

        fun signed(bits: Int): Long {
            val value = next(bits)
            val sign = 1L shl (bits - 1)
            return if (value and sign != 0L) value - (1L shl bits) else value
        }

        /** The count of zeroes before the next one, which is how Rice writes a magnitude. */
        fun unary(): Int {
            var zeroes = 0
            while (next(1) == 0L) {
                zeroes++
                if (zeroes > MAX_UNARY) throw EndOfStream()
            }
            return zeroes
        }

        fun align() {
            if (count % 8 != 0) next(count % 8)
        }

        private fun mask(bits: Int): Long =
            if (bits >= 64) -1L else (1L shl bits) - 1L
    }

    /** A plain stream over the file, so the bit reader can be buffered independently of seeking. */
    private class RafStream(private val raf: RandomAccessFile) : InputStream() {
        override fun read(): Int = raf.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
    }

    private const val BLOCK_STREAMINFO = 0
    private const val BLOCK_SEEKTABLE = 3
    private const val PLACEHOLDER = -1L
    private const val SYNC = 0x3FFEL
    private const val LEFT_SIDE = 8
    private const val RIGHT_SIDE = 9
    private const val MID_SIDE = 10
    private const val MAX_CHANNELS = 8
    private const val MIN_BLOCK = 4096
    private const val READ_BUFFER = 1 shl 16

    /** A magnitude past which the stream is not a stream any more; flac's own limit is far below. */
    private const val MAX_UNARY = 1 shl 20
}
