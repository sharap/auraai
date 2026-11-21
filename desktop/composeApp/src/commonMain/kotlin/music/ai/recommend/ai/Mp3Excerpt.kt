package music.ai.recommend.ai

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.Obuffer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Decodes an mp3 excerpt with a decoder that is the same code everywhere, so that two devices
 * analysing the same file hand the model the same samples.
 *
 * The platform decoders do not. Measured against ffmpeg on the same file, Android's MediaCodec
 * begins its mp3 output 576 samples later — an internal decoder delay that ffmpeg skips and
 * MediaCodec does not — and its seek, which for a variable-bitrate mp3 has to estimate a byte
 * position from the average bitrate, landed a further 666 to 979 samples off. Together that is
 * 26 to 32 ms, and the model charges about 0.95 of cosine for any misalignment past twenty
 * milliseconds, where 0.9985 is the bar for two embeddings describing the same thing. No amount of
 * care on either side fixes that, because neither side is wrong: they are two decoders.
 *
 * So for mp3 both apps use this, and neither uses the platform. Playback is untouched — there the
 * platform decoder is the right tool, and nothing is being compared.
 *
 * Two things follow from owning the decoder. Frames are counted rather than estimated, so the
 * excerpt starts at the sample it is meant to; and the track's length comes from the frames
 * themselves, so the two platforms stop disagreeing about where the middle is — Android reads a
 * duration in microseconds from the container, the desktop's tag reader reports whole seconds.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object Mp3Excerpt {

    /**
     * Written into a synced embedding so a device can tell what produced it. A vector is only
     * accepted from elsewhere when this agrees, which is what keeps a platform decoder's output
     * from being mistaken for this one's.
     */
    const val DECODER = "jlayer-1.0.1"

    fun handles(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() == "mp3"

    /**
     * Layer III carries part of a frame's data in the frames before it — the bit reservoir — so a
     * frame decoded cold is not quite the frame decoded in sequence. Decoding this many frames
     * before the excerpt and discarding them fills the reservoir; ten frames is 260 ms, far more
     * than the two or three the format can reach back, and cheap.
     */
    private const val WARMUP_FRAMES = 10

    /** What the file turned out to be, read off its frames rather than off a tag. */
    private class Layout(
        val sampleRate: Int,
        val channels: Int,
        val samplesPerFrame: Int,
        val frames: Long
    ) {
        val durationMs: Long get() = frames * samplesPerFrame * 1000L / sampleRate
    }

    /**
     * @return exactly [Excerpt.SAMPLES] mono samples at [Excerpt.SAMPLE_RATE], or fewer if the
     *   track is genuinely shorter than the excerpt, or an empty array if it will not decode.
     */
    /**
     * Where the excerpt begins for this file, in milliseconds, by this decoder's own count of its
     * samples — or null for a file it cannot read.
     *
     * The one answer that may be used to name an excerpt for a vector that travels, and the reason
     * is in [decode] two lines below: the window is chosen from the frame walk's own duration and
     * not from whatever the platform says the track's length is. Asking the platform instead looks
     * right and is not. Measured between this phone and one desktop, a tag reader's whole seconds
     * and a container's milliseconds disagree by one second often enough that about a quarter of a
     * library came out one two-second bucket apart — and every one of those vectors was refused as
     * describing a different ten seconds when it described exactly the right one.
     *
     * It costs a walk over the file's frame headers, which is sequential reading and no decoding.
     */
    fun startMs(file: File): Long? {
        val layout = scan(file) ?: return null
        if (layout.frames == 0L) return null
        return Excerpt.startMs(layout.durationMs)
    }

    fun decode(file: File): FloatArray {
        val layout = scan(file) ?: return FloatArray(0)
        if (layout.frames == 0L) return FloatArray(0)

        val startSample = Excerpt.startMs(layout.durationMs) * layout.sampleRate / 1000
        val firstFrame = (startSample / layout.samplesPerFrame - WARMUP_FRAMES).coerceAtLeast(0)
        var toDrop = (startSample - firstFrame * layout.samplesPerFrame).toInt()

        val needed = Excerpt.sourceSamplesNeeded(layout.sampleRate)
        val mono = FloatArray(needed)
        var size = 0

        open(file).use { input ->
            val bitstream = Bitstream(input)
            val buffer = MonoBuffer(layout.channels)
            val decoder = Decoder().apply { setOutputBuffer(buffer) }
            var index = 0L
            try {
                while (size < needed) {
                    val header = bitstream.readFrame() ?: break
                    if (index < firstFrame) {
                        bitstream.closeFrame()
                        index++
                        continue
                    }
                    buffer.reset()
                    try {
                        decoder.decodeFrame(header, bitstream)
                    } finally {
                        bitstream.closeFrame()
                    }
                    index++

                    val frameSize = buffer.size
                    if (toDrop >= frameSize) {
                        toDrop -= frameSize
                        continue
                    }
                    var at = toDrop
                    toDrop = 0
                    while (at < frameSize && size < needed) {
                        mono[size++] = buffer.samples[at++]
                    }
                }
            } catch (e: Exception) {
                // A file that stops decoding part way still has whatever came before it, and that
                // is better than nothing: the excerpt is simply short, which the feature extractor
                // already knows how to handle.
                if (size == 0) return FloatArray(0)
            } finally {
                runCatching { bitstream.close() }
            }
        }

        val collected = if (size == mono.size) mono else mono.copyOf(size)
        return Excerpt.resample(collected, layout.sampleRate)
    }

    /**
     * Walks the frames without decoding them, which is what makes the rest exact: the length is
     * the frames' own, and the frame holding any given sample follows from counting rather than
     * from a byte-position estimate. Costs one sequential read of the file and no arithmetic worth
     * mentioning.
     */
    private fun scan(file: File): Layout? = try {
        open(file).use { input ->
            val bitstream = Bitstream(input)
            var sampleRate = 0
            var channels = 0
            var samplesPerFrame = 0
            var frames = 0L
            try {
                while (true) {
                    val header = bitstream.readFrame() ?: break
                    if (frames == 0L) {
                        // Bitstream hands back the same Header object every time, so what is
                        // needed from it has to be taken now rather than kept as a reference.
                        sampleRate = header.frequency()
                        channels = if (header.mode() == Header.SINGLE_CHANNEL) 1 else 2
                        samplesPerFrame = samplesPerFrame(header)
                    }
                    frames++
                    bitstream.closeFrame()
                }
            } finally {
                runCatching { bitstream.close() }
            }
            if (sampleRate <= 0 || samplesPerFrame <= 0) null
            else Layout(sampleRate, channels, samplesPerFrame, frames)
        }
    } catch (e: Exception) {
        null
    }

    /** How many samples one frame carries, which depends on the layer and the MPEG version. */
    private fun samplesPerFrame(header: Header): Int = when (header.layer()) {
        1 -> 384
        2 -> 1152
        else -> if (header.version() == Header.MPEG1) 1152 else 576
    }

    private fun open(file: File) = BufferedInputStream(FileInputStream(file), READ_BUFFER_BYTES)

    private const val READ_BUFFER_BYTES = 1 shl 16

    /**
     * The decoder's output buffer, kept in floating point and mixed to mono as it arrives.
     *
     * JLayer's own buffer rounds every sample to sixteen bits and clips it, which would put back
     * the overshoot clipping that was just taken out of the desktop's pipeline — a lossy mp3
     * reconstruction regularly exceeds full scale. The samples the decoder passes in are on a
     * +-32768 scale, so dividing by that is all the conversion needed.
     *
     * Each channel has its own write position because the decoder fills them in whatever order it
     * likes, a channel and thirty-two samples at a time.
     */
    private class MonoBuffer(private val channels: Int) : Obuffer() {

        private val perChannel = Array(MAXCHANNELS) { FloatArray(OBUFFERSIZE) }
        private val written = IntArray(MAXCHANNELS)

        /** Mono samples of the frame just decoded, valid up to [size]. */
        val samples = FloatArray(OBUFFERSIZE)

        var size = 0
            private set

        fun reset() {
            written.fill(0)
            size = 0
        }

        override fun append(channel: Int, value: Short) {
            if (channel >= MAXCHANNELS) return
            val at = written[channel]
            if (at < OBUFFERSIZE) {
                perChannel[channel][at] = value / 32768f
                written[channel] = at + 1
            }
        }

        override fun appendSamples(channel: Int, f: FloatArray) {
            if (channel >= MAXCHANNELS) return
            var at = written[channel]
            var i = 0
            while (i < 32 && at < OBUFFERSIZE) {
                perChannel[channel][at++] = f[i++] / 32768f
            }
            written[channel] = at
        }

        override fun write_buffer(value: Int) {
            val count = if (channels == 1) written[0] else minOf(written[0], written[1])
            for (i in 0 until count) {
                var sum = 0f
                for (channel in 0 until channels) sum += perChannel[channel][i]
                samples[i] = sum / channels
            }
            size = count
        }

        override fun clear_buffer() = reset()

        override fun close() = Unit

        override fun set_stop_flag() = Unit
    }
}
