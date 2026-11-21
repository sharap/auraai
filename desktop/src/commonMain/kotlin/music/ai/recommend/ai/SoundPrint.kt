package music.ai.recommend.ai

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * What a recording is, by its own bytes rather than by what anybody wrote about it.
 *
 * A track has to be named to another device, and the two descriptions it can be named by both
 * fail in their own way. Tags and a duration are a judgement call about spelling: measured on one
 * real pair of libraries, 24 tracks had artists that the two tag readers disagreed about — `flёur`
 * against `fl¸ur`, `демо` against `äåìî` — 15 had a title one reader found and the other did not,
 * and 18 had durations more than a second apart because one side reads whole seconds. Worse, tags
 * are not unique: 213 keys on that desktop named more than one file, 452 files in all, and for
 * those the right copy was chosen by whichever duration was closer, which is a coin toss.
 *
 * So the bytes. Not the file's bytes, though — that was the first idea and it is wrong, because a
 * file is mostly tags and artwork at the front, and editing a tag would change the one thing that
 * was supposed to be exact. What is hashed here is the **sound**: the container is walked far
 * enough to find where the audio starts and ends, and a slice from the middle of it is hashed with
 * the sound's length. Nothing a tag editor does moves it.
 *
 * Deliberately **no decoding**. A device only carries vectors for formats it shares a decoder with,
 * but it carries history, playlists and the playlist of the day for everything it can play — and
 * those name tracks too. An identity that needed a decoder would leave exactly the formats that
 * already lose the most with nothing but their spelling. Finding where the sound sits needs no
 * decoder, so this works for an ogg the same as for an mp3.
 *
 * Cheap enough not to think about: a seek and a quarter of a megabyte, 0.19 ms a file on a desktop,
 * about 1 second for a library of four and a half thousand. Hashing whole files would have been
 * 3 ms for an mp3 and 27 ms for a flac — one read of everything — and decoding the excerpt first,
 * which was the other candidate, 39 ms.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object SoundPrint {

    /**
     * How much of the middle of the sound is hashed.
     *
     * A quarter of a megabyte of compressed audio is about fifteen seconds of music and far more
     * than enough to tell two recordings apart: measured over 300 real mp3s, 300 distinct prints.
     * The sound's length goes in too, so a copy that was cut short is never mistaken for the whole.
     */
    const val SLICE_BYTES = 256 * 1024

    /** Where the sound sits in a file: the first byte of audio and how many bytes of it there are. */
    data class Bounds(val at: Long, val bytes: Long)

    /**
     * Which of these tracks have no print worth keeping: none at all, or one taken when the file
     * looked different.
     *
     * The rule rather than the work, and shared for the same reason [AnalysisBacklog.pending] is:
     * a device that decided this differently from its peer would hash different files and name the
     * same recording two ways.
     *
     * @param tracks what this device holds, by whatever it calls a track
     * @param held the stamp each print was taken under; see [AnalysisBacklog.stamp]
     * @param stampOf the stamp of a file right now, or empty for a file that is not there
     */
    fun <K> missing(
        tracks: Map<K, String>,
        held: Map<K, String>,
        stampOf: (String) -> String
    ): List<K> {
        if (tracks.isEmpty()) return emptyList()
        val out = ArrayList<K>()
        for ((track, path) in tracks) {
            val stamp = stampOf(path)
            // Nothing to hash, and nothing to say about it: the file has gone, or is not readable.
            if (stamp.isEmpty()) continue
            if (held[track] == stamp) continue
            out.add(track)
        }
        return out
    }

    /**
     * Tracks whose sound this device has already analysed under another track.
     *
     * One library holds the same recording twice more often than one would think — measured here,
     * 186 sounds of 4371 had a second copy, usually one loose file and one inside a folder — and
     * every copy was analysed again from scratch. Worse, a copy with no vector could never be
     * filled by sync either: a peer served the vector, this device recognised the sound as one it
     * already had, and dropped it as nothing new. 99 tracks sat empty that way with the answer
     * already in the same table.
     *
     * So the answer is local. The work is a lookup, not a model: the same sound has the same
     * vector, which is what a print *means*.
     *
     * @param prints the print of every track that has one
     * @param analysed the tracks that already hold a vector
     * @return each track with no vector, mapped to the track whose vector it may take
     */
    fun <K> twins(prints: Map<K, String>, analysed: Set<K>): Map<K, K> {
        if (prints.isEmpty() || analysed.isEmpty()) return emptyMap()
        val holders = HashMap<String, K>(analysed.size * 2)
        for ((track, print) in prints) {
            if (track !in analysed) continue
            // The first holder of a sound wins, so that two devices reading the same library the
            // same way reach the same answer.
            if (print !in holders) holders[print] = track
        }
        if (holders.isEmpty()) return emptyMap()
        val out = LinkedHashMap<K, K>()
        for ((track, print) in prints) {
            if (track in analysed) continue
            val holder = holders[print] ?: continue
            if (holder == track) continue
            out[track] = holder
        }
        return out
    }

    /**
     * @return the print, lowercase hex, or null for a file that cannot be read or holds no sound.
     */
    fun of(file: File): String? = try {
        RandomAccessFile(file, "r").use { handle ->
            val bounds = bounds(handle, file.length()) ?: return null
            if (bounds.bytes <= 0L) return null
            val wanted = minOf(SLICE_BYTES.toLong(), bounds.bytes).toInt()
            // From the middle, where the excerpt the analysis listens to also comes from. Not the
            // beginning: that is where a container puts what it has to say about itself, and two
            // copies of one recording can differ there without differing as sound.
            val at = bounds.at + (bounds.bytes - wanted) / 2
            val slice = ByteArray(wanted)
            handle.seek(at)
            handle.readFully(slice)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(longBytes(bounds.bytes))
            digest.update(slice)
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Where the sound is, by walking only what every one of these containers says about itself.
     *
     * Each case is the container's own table of contents and nothing more: no decoding, no
     * guessing at frames. A format this does not know falls back to the whole file minus any tag
     * at either end, which is right for one more reason — the formats it does not know are the
     * ones whose vectors stay at home anyway, and what is wanted for them is only that two devices
     * agree.
     */
    fun bounds(handle: RandomAccessFile, length: Long): Bounds? {
        if (length <= 0L) return null
        val start = afterLeadingTag(handle, length)
        val end = beforeTrailingTag(handle, length, start)
        if (end <= start) return null
        val head = ByteArray(12)
        handle.seek(start)
        val read = handle.read(head)
        if (read < 12) return Bounds(start, end - start)
        return when {
            head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() ->
                flac(handle, start, end)
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
                head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() ->
                chunk(handle, start + 12, end, "data")
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() ->
                box(handle, start, end, "mdat")
            else -> Bounds(start, end - start)
        } ?: Bounds(start, end - start)
    }

    /**
     * Past an ID3v2 tag, which is where the artwork lives and therefore most of the bytes of a
     * tagged mp3. Its size is the four synchsafe bytes at offset six: seven bits to a byte, so
     * that no part of it can look like the start of a frame.
     */
    private fun afterLeadingTag(handle: RandomAccessFile, length: Long): Long {
        if (length < 10L) return 0L
        val header = ByteArray(10)
        handle.seek(0L)
        if (handle.read(header) < 10) return 0L
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) return 0L
        var size = 0L
        for (index in 6..9) size = (size shl 7) or (header[index].toLong() and 0x7F)
        // The footer, when the tag says it has one.
        val footer = if ((header[5].toInt() and 0x10) != 0) 10L else 0L
        val at = 10L + size + footer
        return if (at in 0..length) at else 0L
    }

    /** Before an ID3v1 or APE tag at the end, which a tag editor adds and removes at will. */
    private fun beforeTrailingTag(handle: RandomAccessFile, length: Long, start: Long): Long {
        var end = length
        if (end - start >= 128L) {
            val tag = ByteArray(3)
            handle.seek(end - 128L)
            if (handle.read(tag) == 3 && tag[0] == 'T'.code.toByte() &&
                tag[1] == 'A'.code.toByte() && tag[2] == 'G'.code.toByte()
            ) end -= 128L
        }
        if (end - start >= 32L) {
            val footer = ByteArray(8)
            handle.seek(end - 32L)
            if (handle.read(footer) == 8 && String(footer, Charsets.US_ASCII) == "APETAGEX") {
                val sizeBytes = ByteArray(4)
                handle.seek(end - 32L + 12L)
                if (handle.read(sizeBytes) == 4) {
                    var size = 0L
                    for (index in 3 downTo 0) size = (size shl 8) or (sizeBytes[index].toLong() and 0xFF)
                    if (size in 0..(end - start)) end -= size
                }
            }
        }
        return end
    }

    /** FLAC: `fLaC`, then metadata blocks each saying its own length, then the audio. */
    private fun flac(handle: RandomAccessFile, start: Long, end: Long): Bounds? {
        var at = start + 4L
        while (at + 4L <= end) {
            val header = ByteArray(4)
            handle.seek(at)
            if (handle.read(header) < 4) return null
            val last = (header[0].toInt() and 0x80) != 0
            val size = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            at += 4L + size
            if (last) break
        }
        return if (at in start..end) Bounds(at, end - at) else null
    }

    /** RIFF/WAVE: chunks of four-byte names and little-endian lengths; the sound is in `data`. */
    private fun chunk(handle: RandomAccessFile, from: Long, end: Long, wanted: String): Bounds? {
        var at = from
        while (at + 8L <= end) {
            val header = ByteArray(8)
            handle.seek(at)
            if (handle.read(header) < 8) return null
            val name = String(header, 0, 4, Charsets.US_ASCII)
            var size = 0L
            for (index in 7 downTo 4) size = (size shl 8) or (header[index].toLong() and 0xFF)
            if (name == wanted) {
                val bytes = minOf(size, end - at - 8L)
                return if (bytes > 0L) Bounds(at + 8L, bytes) else null
            }
            at += 8L + size + (size and 1L)   // chunks are padded to an even length
        }
        return null
    }

    /** MP4/M4A: boxes of big-endian lengths and four-byte names; the sound is in `mdat`. */
    private fun box(handle: RandomAccessFile, from: Long, end: Long, wanted: String): Bounds? {
        var at = from
        while (at + 8L <= end) {
            val header = ByteArray(8)
            handle.seek(at)
            if (handle.read(header) < 8) return null
            var size = 0L
            for (index in 0..3) size = (size shl 8) or (header[index].toLong() and 0xFF)
            val name = String(header, 4, 4, Charsets.US_ASCII)
            var at64 = at + 8L
            if (size == 1L) {
                // A box too big for 32 bits says so with a 1 and puts the real length after the
                // name. Rare for audio and cheap to honour.
                val large = ByteArray(8)
                handle.seek(at + 8L)
                if (handle.read(large) < 8) return null
                size = 0L
                for (index in 0..7) size = (size shl 8) or (large[index].toLong() and 0xFF)
                at64 = at + 16L
            }
            if (size <= 0L) return null
            if (name == wanted) {
                val bytes = minOf(size - (at64 - at), end - at64)
                return if (bytes > 0L) Bounds(at64, bytes) else null
            }
            at += size
        }
        return null
    }

    private fun longBytes(value: Long) = ByteArray(8) { index ->
        ((value shr (56 - index * 8)) and 0xFF).toByte()
    }
}
