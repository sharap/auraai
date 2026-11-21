package music.ai.recommend.ai

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.Test
import java.io.File
import java.nio.file.Files

/**
 * What a recording is by its own bytes, and what it stops being by somebody's.
 *
 * Built out of synthetic containers rather than real files on purpose: what is being proved is
 * that the walk finds the sound and that nothing a tag editor does moves it, and a tag editor is
 * exactly what a test can imitate exactly. The real files are what the measurements were taken on.
 */
class SoundPrintTest {

    private val sound = ByteArray(600_000) { index -> ((index * 31 + 7) % 251).toByte() }
    private val other = ByteArray(600_000) { index -> ((index * 17 + 3) % 251).toByte() }

    private lateinit var folder: File

    private fun file(name: String, vararg parts: ByteArray): File {
        if (!::folder.isInitialized) folder = Files.createTempDirectory("aura-print").toFile()
        val target = File(folder, name)
        target.outputStream().use { out -> for (part in parts) out.write(part) }
        return target
    }

    /** An ID3v2 header saying how many bytes of tag follow, in synchsafe sevens. */
    private fun id3v2(payload: Int, footer: Boolean = false): ByteArray {
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte(); header[1] = 'D'.code.toByte(); header[2] = '3'.code.toByte()
        header[3] = 3; header[4] = 0
        header[5] = if (footer) 0x10 else 0
        var size = payload
        for (index in 9 downTo 6) {
            header[index] = (size and 0x7F).toByte()
            size = size shr 7
        }
        // A tag that says it has a footer has one: ten bytes of `3DI` and the same size again.
        val tail = if (footer) "3DI".toByteArray(Charsets.US_ASCII) + header.copyOfRange(3, 10)
        else ByteArray(0)
        return header + ByteArray(payload) { 0x41 } + tail
    }

    private fun id3v1(): ByteArray =
        "TAG".toByteArray(Charsets.US_ASCII) + ByteArray(125) { 0x42 }

    private fun flacBlock(type: Int, size: Int, last: Boolean): ByteArray {
        val header = byteArrayOf(
            ((if (last) 0x80 else 0) or type).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte()
        )
        return header + ByteArray(size) { 0x43 }
    }

    private fun flac(commentBytes: Int): ByteArray =
        "fLaC".toByteArray(Charsets.US_ASCII) +
            flacBlock(type = 0, size = 34, last = false) +
            flacBlock(type = 4, size = commentBytes, last = true)

    private fun riffChunk(name: String, payload: ByteArray): ByteArray {
        val header = ByteArray(8)
        name.toByteArray(Charsets.US_ASCII).copyInto(header)
        var size = payload.size
        for (index in 4..7) {
            header[index] = (size and 0xFF).toByte()
            size = size shr 8
        }
        return header + payload
    }

    private fun mp4Box(name: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        val header = ByteArray(8)
        for (index in 0..3) header[index] = ((size shr (24 - index * 8)) and 0xFF).toByte()
        name.toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        return header + payload
    }

    @Test
    fun `the same sound under different tags is the same recording`() {
        val plain = file("plain.mp3", sound)
        val tagged = file("tagged.mp3", id3v2(payload = 300_000), sound)
        val both = file("both.mp3", id3v2(payload = 12), sound, id3v1())
        val withFooter = file("footer.mp3", id3v2(payload = 40, footer = true), sound)

        val print = SoundPrint.of(plain)
        assertEquals(print, SoundPrint.of(tagged))
        assertEquals(print, SoundPrint.of(both))
        assertEquals(print, SoundPrint.of(withFooter))
    }

    @Test
    fun `another recording is another print, and so is a copy cut short`() {
        val one = file("one.mp3", id3v2(payload = 100), sound)
        val two = file("two.mp3", id3v2(payload = 100), other)
        assertNotEquals(SoundPrint.of(one), SoundPrint.of(two))

        // The sound's length goes into the digest, so half a file is not the file. Both halves
        // hold the same bytes around the middle of *their* sound, which is what makes this worth
        // a test rather than an assumption.
        val short = file("short.mp3", sound.copyOf(400_000))
        assertNotEquals(SoundPrint.of(file("whole.mp3", sound)), SoundPrint.of(short))
    }

    @Test
    fun `flac is found past its metadata, however much of it there is`() {
        val small = file("small.flac", flac(commentBytes = 40), sound)
        val large = file("large.flac", flac(commentBytes = 120_000), sound)
        assertEquals(SoundPrint.of(small), SoundPrint.of(large))
        // And it is the sound that was found, not the file: the same sound in an mp3 agrees.
        assertEquals(SoundPrint.of(file("same.mp3", sound)), SoundPrint.of(small))
    }

    @Test
    fun `wav is the data chunk and not the chunks around it`() {
        val quiet = file(
            "quiet.wav",
            "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "WAVE".toByteArray(Charsets.US_ASCII),
            riffChunk("fmt ", ByteArray(16) { 0x44 }),
            riffChunk("data", sound)
        )
        val chatty = file(
            "chatty.wav",
            "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "WAVE".toByteArray(Charsets.US_ASCII),
            riffChunk("fmt ", ByteArray(16) { 0x44 }),
            riffChunk("LIST", ByteArray(5_000) { 0x45 }),
            riffChunk("data", sound),
            riffChunk("id3 ", ByteArray(2_000) { 0x46 })
        )
        assertEquals(SoundPrint.of(quiet), SoundPrint.of(chatty))
        assertEquals(SoundPrint.of(file("same.mp3", sound)), SoundPrint.of(quiet))
    }

    @Test
    fun `m4a is the mdat box, before or after everything else`() {
        val tagsFirst = file(
            "first.m4a",
            mp4Box("ftyp", ByteArray(8) { 0x47 }),
            mp4Box("moov", ByteArray(50_000) { 0x48 }),
            mp4Box("mdat", sound)
        )
        val tagsLast = file(
            "last.m4a",
            mp4Box("ftyp", ByteArray(8) { 0x47 }),
            mp4Box("mdat", sound),
            mp4Box("moov", ByteArray(90_000) { 0x49 })
        )
        assertEquals(SoundPrint.of(tagsFirst), SoundPrint.of(tagsLast))
        assertEquals(SoundPrint.of(file("same.mp3", sound)), SoundPrint.of(tagsFirst))
    }

    @Test
    fun `a container this does not know is still the same on both devices`() {
        // No walk to do, so the whole file past its tags — which is all two devices need to agree
        // on for a format whose vectors stay at home anyway.
        val one = file("one.ogg", id3v2(payload = 64), sound)
        val two = file("two.ogg", id3v2(payload = 64), sound)
        assertEquals(SoundPrint.of(one), SoundPrint.of(two))
        assertNotEquals(SoundPrint.of(one), SoundPrint.of(file("three.ogg", id3v2(payload = 64), other)))
    }

    @Test
    fun `nothing to read is nothing to say`() {
        assertNull(SoundPrint.of(File(folderOrTemp(), "не существует.mp3")))
        assertNull(SoundPrint.of(file("empty.mp3", ByteArray(0))))
        // A tag and nothing else is a file with no sound in it.
        assertNull(SoundPrint.of(file("tag-only.mp3", id3v2(payload = 100))))
    }

    @Test
    fun `a print is taken once and taken again when the file changes`() {
        val tracks = mapOf(1L to "/music/one.mp3", 2L to "/music/two.mp3", 3L to "/music/gone.mp3")
        val stamps = mapOf("/music/one.mp3" to "100:7", "/music/two.mp3" to "200:9")
        val stampOf = { path: String -> stamps[path] ?: "" }

        // Nothing held yet: everything that is there is wanted. The file that is not there is not
        // wanted, and is not an error either.
        assertEquals(listOf(1L, 2L), SoundPrint.missing(tracks, emptyMap(), stampOf))

        // Held under the same stamp: nothing to do, which is what makes this cheap to call often.
        val held = mapOf(1L to "100:7", 2L to "200:9")
        assertEquals(emptyList<Long>(), SoundPrint.missing(tracks, held, stampOf))

        // The file was written to, so what was hashed is not what is there now.
        assertEquals(listOf(2L), SoundPrint.missing(tracks, held + (2L to "200:10"), stampOf))
    }

    @Test
    fun `a second copy of a sound takes the vector the first one already has`() {
        val prints = mapOf(
            1L to "аа", 2L to "аа",     // один звук, две копии: одна посчитана
            3L to "бб",                  // посчитан, копий нет
            4L to "вв", 5L to "вв",     // один звук, две копии, ни одной не посчитано
            6L to "гг"                   // не посчитан, копий нет
        )
        val analysed = setOf(1L, 3L)

        assertEquals(mapOf(2L to 1L), SoundPrint.twins(prints, analysed))

        // Nothing analysed, nothing to copy — and no answer invented out of two empty copies.
        assertEquals(emptyMap<Long, Long>(), SoundPrint.twins(prints, emptySet()))

        // The first holder of a sound wins, so two devices reading one library the same way reach
        // the same answer rather than each picking a copy.
        val three = mapOf(7L to "дд", 8L to "дд", 9L to "дд")
        assertEquals(mapOf(8L to 7L, 9L to 7L), SoundPrint.twins(three, setOf(7L)))
    }

    private fun folderOrTemp(): File {
        if (!::folder.isInitialized) folder = Files.createTempDirectory("aura-print").toFile()
        return folder
    }
}
