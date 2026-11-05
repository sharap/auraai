package music.ai.recommend.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Writes out the PCM this device actually feeds the model, so it can be compared with what the
 * desktop feeds it for the same track.
 *
 * Measured against the desktop, the same track's embedding agrees only to a cosine of 0.92 — far
 * from the 0.9985 the app itself treats as "no re-analysis needed". The feature extractor is the
 * same code on both sides and two independent mp3 decoders agree to 0.999999, so the difference
 * has to be in which samples reach the extractor. Cross-correlating these dumps against ffmpeg's
 * output gives the two numbers that settle it: how far the two ten-second windows are offset, and
 * how well the audio agrees once that offset is removed.
 *
 * Opt-in, like the other experiments here:
 *
 *     am instrument -w -e bench true \
 *       -e class music.ai.recommend.ai.DecodeDumpTest \
 *       music.ai.recommend.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DecodeDumpTest {

    private val tag = "DecodeDump"

    @Test
    fun dumpDecodedExcerpts() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val songs = music.ai.recommend.scanner.MusicScanner().scanMusic(context)
            .flatMap { it.songs }
            .distinctBy { it.id }
            .sortedBy { it.path }
        assumeTrue("no music on the device", songs.isNotEmpty())

        // A few of each container: the disagreement follows the format (wav .976, mp3 .922,
        // m4a .864), so whatever is wrong should show up differently in each.
        val chosen = buildList {
            for (extension in listOf("mp3", "m4a", "wav", "flac", "ogg", "opus")) {
                addAll(songs.filter { it.path.substringAfterLast('.', "").lowercase() == extension }
                    .take(if (extension == "mp3") 4 else 2))
            }
        }
        Log.i(tag, "dumping ${chosen.size} tracks")

        val scanner = AiScanner(context, ModelRepository.getInstance(context), EmbeddingStore.getInstance(context))
        val outputDir = File(context.cacheDir, "decode-dump").apply { mkdirs() }
        val manifest = StringBuilder()

        for ((index, song) in chosen.withIndex()) {
            val probe = probe(song.path)
            val samples = runCatching { scanner.decodeForBenchmark(song) }.getOrElse {
                Log.w(tag, "decode failed for ${song.path}", it)
                FloatArray(0)
            }
            if (samples.isEmpty()) continue

            val file = File(outputDir, "track$index.f32")
            // Little-endian float32, the same way the vectors are stored, so numpy reads it as it stands.
            ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).let { buffer ->
                buffer.asFloatBuffer().put(samples)
                file.writeBytes(buffer.array())
            }
            manifest.append(
                listOf(
                    file.name,
                    song.path,
                    probe.durationUs,
                    probe.startUs,
                    probe.sampleRate,
                    probe.channels,
                    samples.size
                ).joinToString("\t")
            ).append('\n')
            Log.i(tag, "${file.name}: ${song.path} start=${probe.startUs}us samples=${samples.size}")
        }

        File(outputDir, "manifest.tsv").writeText(
            "file\tpath\tdurationUs\tstartUs\tsourceRate\tchannels\tsamples\n$manifest"
        )
        Log.i(tag, "written to ${outputDir.absolutePath}")
    }

    private companion object {
        /** Offsets from the usual start, in milliseconds. Dense where the two sides differ. */
        val OFFSETS_MS = listOf(-2000, -1000, -500, -250, -150, -60, -25, 0, 25, 60, 150, 250, 500, 1000, 2000)
    }

    /**
     * How far does an embedding move when the excerpt moves?
     *
     * This is the experiment that settles whether embeddings can be carried between devices. The
     * two sides agree on the samples -- at the same requested start the phone's PCM and ffmpeg's
     * agree to 0.995-1.0000, and two independent mp3 decoders agree to 0.999999 -- yet the stored
     * embeddings for the same track agree only to 0.92. If a window shifted by the tens of
     * milliseconds the two sides differ by is enough to account for that, the fix is to pin down
     * the excerpt. If it is not, something other than the samples is at fault.
     *
     *     am instrument -w -e bench true \
     *       -e class music.ai.recommend.ai.DecodeDumpTest#sweepExcerptOffset \
     *       music.ai.recommend.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun sweepExcerptOffset() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val songs = music.ai.recommend.scanner.MusicScanner().scanMusic(context)
            .flatMap { it.songs }
            .distinctBy { it.id }
            .sortedBy { it.path }
        assumeTrue("no music on the device", songs.isNotEmpty())

        val wanted = (args.getString("tracks") ?: "").split(",").filter { it.isNotBlank() }
        val chosen = if (wanted.isEmpty()) {
            listOf("mp3", "m4a", "wav").mapNotNull { extension ->
                songs.firstOrNull { it.path.substringAfterLast('.', "").lowercase() == extension }
            }
        } else {
            wanted.mapNotNull { needle -> songs.firstOrNull { it.path.contains(needle) } }
        }
        assumeTrue("nothing to sweep", chosen.isNotEmpty())

        val models = ModelRepository.getInstance(context)
        val variant = models.audioVariant.value
        val modelFile = runBlocking { models.fileForInference(variant.asset) }
        assumeTrue("no audio model on the device", modelFile != null)

        // -e offsets "-84629,0,30000" walks the whole track instead of the default fine sweep,
        // which is how one finds out which excerpt another implementation actually used.
        val offsets = (args.getString("offsets") ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { OFFSETS_MS }

        val scanner = AiScanner(context, models, EmbeddingStore.getInstance(context))
        val processor = AudioProcessor()
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(modelFile!!.absolutePath, OrtSession.SessionOptions())
        val outputDir = File(context.cacheDir, "sweep").apply { mkdirs() }
        val manifest = StringBuilder("file\tpath\tsongId\toffsetMs\tstartUs\tsamples\tvariant\n")

        try {
            for ((index, song) in chosen.withIndex()) {
                val probe = probe(song.path)
                for (offsetMs in offsets) {
                    val startUs = max(0L, probe.startUs + offsetMs * 1000L)
                    val pcm = runCatching { scanner.decodeForBenchmark(song, null, startUs) }
                        .getOrElse { FloatArray(0) }
                    if (pcm.isEmpty()) continue
                    val features = processor.extractFeatures(pcm)
                    val vector = OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(features), longArrayOf(1, 1, 1001, 64)
                    ).use { tensor ->
                        session.run(mapOf("input_features" to tensor)).use { output ->
                            @Suppress("UNCHECKED_CAST")
                            (output.get(0).value as Array<FloatArray>)[0]
                        }
                    }
                    val name = "track$index@$offsetMs.vec"
                    ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).let { buffer ->
                        buffer.asFloatBuffer().put(vector)
                        File(outputDir, name).writeBytes(buffer.array())
                    }
                    manifest.append(
                        listOf(name, song.path, song.id, offsetMs, startUs, pcm.size, variant.name)
                            .joinToString("\t")
                    ).append('\n')
                    Log.i(tag, "$name start=${startUs}us samples=${pcm.size}")
                }
            }
        } finally {
            runCatching { session.close() }
        }
        File(outputDir, "manifest.tsv").writeText(manifest.toString())
        Log.i(tag, "swept into ${outputDir.absolutePath}, model=${modelFile.name}")
    }

    private class Probe(
        val durationUs: Long,
        val startUs: Long,
        val sampleRate: Int,
        val channels: Int
    )

    /**
     * The same numbers the scanner works from: the container's own duration, and the start of the
     * excerpt it derives from it. Recomputed here rather than reported by the scanner, so that
     * nothing in the production path changes for the sake of a measurement.
     */
    private fun probe(path: String): Probe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                return Probe(
                    durationUs = duration,
                    startUs = Excerpt.startMs(duration / 1000) * 1000,
                    sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        0
                    },
                    channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else {
                        0
                    }
                )
            }
            return Probe(0, 0, 0, 0)
        } finally {
            extractor.release()
        }
    }
}
