package music.ai.recommend.ai

import net.sourceforge.jaad.aac.Decoder
import net.sourceforge.jaad.aac.SampleBuffer
import java.io.File
import java.io.RandomAccessFile

/**
 * Decodes an m4a excerpt with a decoder that is the same code everywhere, so that two devices
 * analysing the same file hand the model the same samples.
 *
 * This is the one format where that cannot be arrived at by care. mp3 needed a shared decoder
 * because the platforms disagree about where a frame begins; flac and wav needed one because the
 * window and the resampler differed while the samples did not. AAC is lossy and its specification
 * does not define the output exactly — it defines how close a decoder has to be — so two correct
 * decoders of the same file produce two different signals on purpose. There is nothing to align.
 * The only answer is for both apps to run the same decoder, which is what this does.
 *
 * The decoder is the pure-Java one that jcodec carries, `net.sourceforge.jaad.aac`. The library
 * takes the frames and the configuration; the container is this file's own work, as it is for flac,
 * because the container is where the two things the embedding depends on are decided: how long the
 * track is, and which frame the excerpt starts in. A container walk also costs nothing next to
 * decoding.
 *
 * The encoder's priming — the samples it put in front of the audio to fill its own filters — is
 * skipped by what the edit list says, so the excerpt starts where the music starts and not forty
 * milliseconds earlier. Both devices read the same number out of the same file, so even a file
 * whose priming is written in some way this does not understand comes out the same on both.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object M4aExcerpt {

    /**
     * Named for the artifact rather than for the upstream library: jcodec carries its own copy of
     * jaad, and what has to agree between two devices is the bytes they both run.
     */
    const val DECODER = "jaad-jcodec-0.2.5"

    fun handles(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("m4a", "m4b")

    /** Where the excerpt begins, by the container's own count of the audio, or null if unreadable. */
    fun startMs(file: File): Long? {
        val track = runCatching { RandomAccessFile(file, "r").use { track(it) } }.getOrNull() ?: return null
        if (track.durationMs <= 0L) return null
        return Excerpt.startMs(track.durationMs)
    }

    /**
     * @return exactly [Excerpt.SAMPLES] mono samples at [Excerpt.SAMPLE_RATE], or fewer if the
     *   track is genuinely shorter than the excerpt, or an empty array if it will not decode.
     */
    fun decode(file: File): FloatArray = try {
        RandomAccessFile(file, "r").use { raf ->
            val track = track(raf) ?: return FloatArray(0)
            if (track.sampleRate <= 0 || track.config.isEmpty()) return FloatArray(0)

            val startSample = Excerpt.startMs(track.durationMs) * track.sampleRate / 1000
            val needed = Excerpt.sourceSamplesNeeded(track.sampleRate)
            // The priming is audio the encoder added in front of the music; the window is measured
            // from the music.
            val wanted = startSample + track.priming

            // The frame holding that sample, and how far into it to start. Walked rather than
            // divided, because nothing says every frame of a file holds the same count — it is
            // 1024 for every AAC-LC encoder anybody uses, and a walk over a few thousand entries
            // costs nothing against decoding one.
            var frame = 0
            var frameStart = 0L
            while (frame < track.frames && frameStart + track.lengthOf(frame) <= wanted) {
                frameStart += track.lengthOf(frame)
                frame++
            }
            if (frame >= track.frames) return FloatArray(0)
            var toDrop = (wanted - frameStart).toInt()

            val decoder = Decoder(track.config)
            val buffer = SampleBuffer().apply { setBigEndian(false) }
            val mono = FloatArray(needed)
            var size = 0

            while (size < needed && frame < track.frames) {
                val bytes = track.read(raf, frame) ?: break
                frame++
                try {
                    decoder.decodeFrame(bytes, buffer)
                } catch (e: Exception) {
                    // A frame the decoder will not take. Stopping is right: what follows it would
                    // be audio from somewhere else in the track, and an excerpt of the wrong ten
                    // seconds is worse than a short one.
                    break
                }
                val pcm = buffer.data ?: break
                val channels = buffer.channels
                if (channels <= 0) break
                val frames = pcm.size / 2 / channels
                var at = toDrop
                toDrop = 0
                while (at < frames && size < needed) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        val index = ((at * channels) + channel) * 2
                        val value = ((pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)).toShort()
                        sum += value / 32768f
                    }
                    mono[size++] = sum / channels
                    at++
                }
            }

            Excerpt.resample(if (size == mono.size) mono else mono.copyOf(size), track.sampleRate)
        }
    } catch (e: Exception) {
        FloatArray(0)
    }

    // ------------------------------------------------------------------------- the container

    /**
     * Everything the decode needs out of the file's boxes: where the frames are, how long they
     * are, and what the decoder has to be told before it sees any of them.
     */
    private class Track(
        val sampleRate: Int,
        val timescale: Int,
        /** The decoder's configuration, two or more bytes out of the `esds` box. */
        val config: ByteArray,
        /** Samples the encoder put in front of the music; see [priming] in [track]. */
        val priming: Long,
        /**
         * How many samples of music there are, when the file says so exactly, or zero.
         *
         * iTunes writes it, and it is worth more than the sum of the frames: the encoder's last
         * frame is part empty and the first is priming, so the frames count more samples than
         * there is music.
         */
        val playable: Long,
        /** How many samples each frame holds, run-length encoded as the `stts` box writes it. */
        private val lengths: List<Pair<Int, Int>>,
        private val sizes: IntArray,
        private val offsets: LongArray
    ) {
        val frames: Int get() = sizes.size

        val totalSamples: Long get() = lengths.sumOf { (count, length) -> count.toLong() * length }

        /**
         * The playable length: what the file says, or the frames minus the priming when it does
         * not say.
         *
         * The fallback leaves the trailing padding in — the encoder's last, part-empty frame — at
         * most twenty-three milliseconds. Both devices read the same number either way, which is
         * what the excerpt needs; being right as well is what [playable] buys.
         */
        val durationMs: Long get() = when {
            timescale <= 0 -> 0L
            playable > 0L -> playable * 1000L / timescale
            else -> (totalSamples - priming) * 1000L / timescale
        }

        fun lengthOf(frame: Int): Int {
            var seen = 0
            for ((count, length) in lengths) {
                if (frame < seen + count) return length
                seen += count
            }
            return lengths.lastOrNull()?.second ?: 1024
        }

        fun read(raf: RandomAccessFile, frame: Int): ByteArray? {
            if (frame !in sizes.indices) return null
            val size = sizes[frame]
            if (size <= 0 || size > MAX_FRAME) return null
            val bytes = ByteArray(size)
            raf.seek(offsets[frame])
            var filled = 0
            while (filled < size) {
                val read = raf.read(bytes, filled, size - filled)
                if (read <= 0) return null
                filled += read
            }
            return bytes
        }
    }

    /** @return the audio track, or null when the file holds none this can read. */
    private fun track(raf: RandomAccessFile): Track? {
        var sampleRate = 0
        var timescale = 0
        var config: ByteArray? = null
        var priming = 0L
        var lengths: List<Pair<Int, Int>> = emptyList()
        var sizes: IntArray? = null
        var chunkOffsets: LongArray? = null
        var samplesPerChunk: List<Triple<Int, Int, Int>> = emptyList()
        var audio = false
        var playable = 0L

        fun walk(from: Long, to: Long, depth: Int) {
            if (depth > MAX_DEPTH) return
            var at = from
            while (at + 8 <= to) {
                raf.seek(at)
                val header = ByteArray(8)
                if (raf.read(header) != 8) return
                var size = be(header, 0, 4)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                var body = at + 8
                if (size == 1L) {
                    val extended = ByteArray(8)
                    if (raf.read(extended) != 8) return
                    size = be(extended, 0, 8)
                    body = at + 16
                }
                if (size == 0L) size = to - at
                if (size < 8 || at + size > to) return
                val end = at + size

                when (type) {
                    "moov", "trak", "mdia", "minf", "stbl", "edts" -> walk(body, end, depth + 1)
                    "udta" -> {
                        // Where iTunes writes what the container does not say: the encoder's
                        // priming and the true number of samples. Scanned rather than walked,
                        // because the path to it — meta, ilst, a free-form box with a name and a
                        // mean — is four levels of boxes that exist only to hold one string, and
                        // every encoder nests them slightly differently.
                        val length = (end - body).coerceAtMost(MAX_UDTA.toLong()).toInt()
                        if (length > 0) {
                            val bytes = ByteArray(length)
                            raf.readFully(bytes)
                            gapless(bytes)?.let { (delay, samples) ->
                                if (priming == 0L) priming = delay
                                playable = samples
                            }
                        }
                    }
                    "hdlr" -> {
                        val bytes = ByteArray((end - body).coerceAtMost(12L).toInt())
                        raf.readFully(bytes)
                        if (bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "soun") audio = true
                    }
                    "mdhd" -> {
                        val bytes = ByteArray((end - body).coerceAtMost(24L).toInt())
                        raf.readFully(bytes)
                        // Version one writes the times as 64-bit; the timescale sits after them.
                        timescale = if (bytes.isNotEmpty() && bytes[0].toInt() == 1) {
                            if (bytes.size >= 24) be(bytes, 20, 4).toInt() else 0
                        } else {
                            if (bytes.size >= 16) be(bytes, 12, 4).toInt() else 0
                        }
                    }
                    "elst" -> {
                        val bytes = ByteArray((end - body).coerceAtMost(32L).toInt())
                        raf.readFully(bytes)
                        // The first entry's media time is where the music starts, which is to say
                        // how much of the front is the encoder's own doing.
                        if (bytes.size >= 20) {
                            priming = if (bytes[0].toInt() == 1) {
                                if (bytes.size >= 28) be(bytes, 16, 8) else 0L
                            } else {
                                be(bytes, 12, 4)
                            }.coerceAtLeast(0L)
                        }
                    }
                    "stsd" -> {
                        // Past the entry count, into the first entry, which for audio is an
                        // `mp4a` box with the configuration inside it.
                        walk(body + 8, end, depth + 1)
                    }
                    "mp4a" -> {
                        val bytes = ByteArray(28)
                        raf.readFully(bytes)
                        sampleRate = be(bytes, 24, 2).toInt()
                        walk(body + 28, end, depth + 1)
                    }
                    "esds" -> {
                        val bytes = ByteArray((end - body).toInt().coerceAtMost(512))
                        raf.readFully(bytes)
                        config = audioSpecificConfig(bytes)
                    }
                    "stts" -> {
                        val bytes = ByteArray((end - body).toInt().coerceAtMost(1 shl 20))
                        raf.readFully(bytes)
                        val count = be(bytes, 4, 4).toInt()
                        val out = ArrayList<Pair<Int, Int>>(count.coerceAtMost(1 shl 16))
                        for (index in 0 until count) {
                            val at2 = 8 + index * 8
                            if (at2 + 8 > bytes.size) break
                            out.add(be(bytes, at2, 4).toInt() to be(bytes, at2 + 4, 4).toInt())
                        }
                        lengths = out
                    }
                    "stsz" -> {
                        val bytes = ByteArray((end - body).toInt().coerceAtMost(1 shl 22))
                        raf.readFully(bytes)
                        val uniform = be(bytes, 4, 4).toInt()
                        val count = be(bytes, 8, 4).toInt().coerceAtMost(MAX_FRAMES)
                        sizes = IntArray(count) { index ->
                            if (uniform != 0) uniform else {
                                val at2 = 12 + index * 4
                                if (at2 + 4 <= bytes.size) be(bytes, at2, 4).toInt() else 0
                            }
                        }
                    }
                    "stsc" -> {
                        val bytes = ByteArray((end - body).toInt().coerceAtMost(1 shl 20))
                        raf.readFully(bytes)
                        val count = be(bytes, 4, 4).toInt()
                        val out = ArrayList<Triple<Int, Int, Int>>(count.coerceAtMost(1 shl 16))
                        for (index in 0 until count) {
                            val at2 = 8 + index * 12
                            if (at2 + 12 > bytes.size) break
                            out.add(
                                Triple(
                                    be(bytes, at2, 4).toInt(),
                                    be(bytes, at2 + 4, 4).toInt(),
                                    be(bytes, at2 + 8, 4).toInt()
                                )
                            )
                        }
                        samplesPerChunk = out
                    }
                    "stco", "co64" -> {
                        val bytes = ByteArray((end - body).toInt().coerceAtMost(1 shl 22))
                        raf.readFully(bytes)
                        val wide = type == "co64"
                        val step = if (wide) 8 else 4
                        val count = be(bytes, 4, 4).toInt().coerceAtMost(MAX_FRAMES)
                        chunkOffsets = LongArray(count) { index ->
                            val at2 = 8 + index * step
                            if (at2 + step <= bytes.size) be(bytes, at2, step) else 0L
                        }
                    }
                }
                at = end
            }
        }

        walk(0L, raf.length(), 0)

        val frameSizes = sizes ?: return null
        val chunks = chunkOffsets ?: return null
        val specific = config ?: return null
        if (!audio || frameSizes.isEmpty() || chunks.isEmpty()) return null
        if (sampleRate <= 0) sampleRate = timescale
        if (timescale <= 0) timescale = sampleRate
        if (sampleRate <= 0) return null

        return Track(
            sampleRate = sampleRate,
            timescale = timescale,
            config = specific,
            priming = priming,
            playable = playable,
            lengths = lengths.ifEmpty { listOf(frameSizes.size to 1024) },
            sizes = frameSizes,
            offsets = offsetsOf(frameSizes, chunks, samplesPerChunk)
        )
    }

    /**
     * Where each frame sits in the file.
     *
     * The container says it in two halves — which chunk each run of frames belongs to, and where
     * each chunk begins — so this puts them together once rather than resolving it per frame.
     */
    private fun offsetsOf(
        sizes: IntArray,
        chunks: LongArray,
        samplesPerChunk: List<Triple<Int, Int, Int>>
    ): LongArray {
        val offsets = LongArray(sizes.size)
        var frame = 0
        var entry = 0
        var chunk = 0
        while (frame < sizes.size && chunk < chunks.size) {
            // The run this chunk belongs to: entries say "from this chunk on, so many per chunk".
            while (entry + 1 < samplesPerChunk.size && samplesPerChunk[entry + 1].first <= chunk + 1) {
                entry++
            }
            val perChunk = samplesPerChunk.getOrNull(entry)?.second ?: sizes.size
            var at = chunks[chunk]
            var taken = 0
            while (taken < perChunk && frame < sizes.size) {
                offsets[frame] = at
                at += sizes[frame]
                frame++
                taken++
            }
            chunk++
        }
        return offsets
    }

    /**
     * The decoder's configuration, dug out of the descriptors in an `esds` box.
     *
     * The box is a little tree of tagged, variable-length descriptors, and what is wanted is the
     * innermost one — tag five, the audio specific config, which says the profile, the sample rate
     * and the channels. Two bytes, usually.
     */
    private fun audioSpecificConfig(bytes: ByteArray): ByteArray? {
        var at = 4                                    // past the box's version and flags
        while (at < bytes.size) {
            val tag = bytes[at].toInt() and 0xFF
            at++
            var length = 0
            var more = true
            var guard = 0
            while (more && at < bytes.size && guard < 4) {
                val byte = bytes[at].toInt() and 0xFF
                length = (length shl 7) or (byte and 0x7F)
                more = (byte and 0x80) != 0
                at++
                guard++
            }
            when (tag) {
                TAG_ES -> at += 3                     // an id and a flags byte, then more descriptors
                TAG_DECODER_CONFIG -> at += 13        // the object type, the buffer sizes, the rates
                TAG_SPECIFIC_INFO -> {
                    val end = (at + length).coerceAtMost(bytes.size)
                    return if (end > at) bytes.copyOfRange(at, end) else null
                }
                else -> at += length
            }
        }
        return null
    }

    /**
     * The encoder's priming and the true sample count, out of iTunes' `iTunSMPB` string.
     *
     * The string is a row of hexadecimal fields, of which the second is the priming, the third the
     * padding at the end, and the fourth how many samples of music there are. Nothing else in an
     * m4a says any of it: there is an edit list for the same purpose, and the files this was
     * written for do not carry one — which is how the excerpt came to start forty-eight
     * milliseconds into the music on one device and at the music on the other.
     *
     * @return the priming and the sample count, or null when the tag is not there or will not read.
     */
    private fun gapless(bytes: ByteArray): Pair<Long, Long>? {
        val marker = "iTunSMPB".toByteArray(Charsets.US_ASCII)
        val name = indexOf(bytes, marker, 0)
        if (name < 0) return null
        val data = indexOf(bytes, "data".toByteArray(Charsets.US_ASCII), name)
        if (data < 4) return null
        val size = be(bytes, data - 4, 4).toInt()
        // Past the box's type and its locale, which are four bytes each.
        val from = data + 4 + 8
        val to = (data - 4 + size).coerceAtMost(bytes.size)
        if (from >= to) return null
        val fields = String(bytes, from, to - from, Charsets.US_ASCII)
            .trim()
            .split(' ', '\t', '\n')
            .filter { it.isNotEmpty() }
        if (fields.size < 4) return null
        val delay = fields[1].toLongOrNull(16) ?: return null
        val samples = fields[3].toLongOrNull(16) ?: return null
        if (delay < 0 || samples < 0) return null
        return delay to samples
    }

    private fun indexOf(bytes: ByteArray, what: ByteArray, from: Int): Int {
        outer@ for (at in from..bytes.size - what.size) {
            for (index in what.indices) {
                if (bytes[at + index] != what[index]) continue@outer
            }
            return at
        }
        return -1
    }

    private fun be(bytes: ByteArray, at: Int, count: Int): Long {
        var value = 0L
        for (index in 0 until count) value = (value shl 8) or (bytes[at + index].toLong() and 0xFFL)
        return value
    }

    private const val TAG_ES = 0x03
    private const val TAG_DECODER_CONFIG = 0x04
    private const val TAG_SPECIFIC_INFO = 0x05
    private const val MAX_DEPTH = 8
    private const val MAX_FRAME = 1 shl 20
    private const val MAX_FRAMES = 1 shl 21

    /** Enough for any tag worth reading, and a bound on a box that can hold cover art. */
    private const val MAX_UDTA = 1 shl 20
}
