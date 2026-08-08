package music.ai.recommend.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.ai.recommend.db.AppDatabase
import music.ai.recommend.db.EmbeddingEntity
import music.ai.recommend.model.Song
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max

class AiScanner(private val context: Context) {
    private val modelFileName = "audio_model.onnx"
    private val db = AppDatabase.getDatabase(context)
    private val audioProcessor = AudioProcessor()
    
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    
    private var isStopping = false

    private fun loadModel(onStatus: (String) -> Unit) {
        if (ortSession != null) return
        try {
            val cacheModelFile = File(context.cacheDir, modelFileName)
            if (!cacheModelFile.exists()) {
                onStatus("Copying AI model from assets (first time)...")
                context.assets.open(modelFileName).use { input ->
                    cacheModelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            onStatus("Initializing AI engine...")
            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                options.addNnapi() // Enable hardware acceleration
            } catch (e: Exception) {
                Log.w("AiScanner", "NNAPI not available, using CPU")
            }
            ortSession = ortEnv?.createSession(cacheModelFile.absolutePath, options)
            Log.d("AiScanner", "ONNX Model loaded successfully")
        } catch (e: Exception) {
            Log.e("AiScanner", "Failed to load ONNX model", e)
            onStatus("Error loading AI model")
        }
    }

    fun stop() {
        isStopping = true
    }

    suspend fun scanSongs(
        songs: List<Song>,
        onProgress: (Float, String, Int, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        isStopping = false
        
        loadModel { status ->
            onProgress(0f, status, 0, -1L)
        }

        val dao = db.embeddingDao()
        val scannedIds = dao.getAll().map { it.songId }.toSet()
        val songsToProcess = songs.filter { it.id !in scannedIds }
        val totalToProcess = songsToProcess.size
        
        if (totalToProcess == 0) {
            onProgress(1f, "Complete", songs.size, 0L)
            return@withContext
        }

        val startTime = System.currentTimeMillis()
        
        for ((index, song) in songsToProcess.withIndex()) {
            if (isStopping) break
            
            val currentTime = System.currentTimeMillis()
            val elapsed = currentTime - startTime
            val avgTimePerSong = if (index > 0) elapsed / index else 0L
            val remainingCount = totalToProcess - index
            val etrSeconds = (remainingCount * avgTimePerSong) / 1000
            
            onProgress(
                index.toFloat() / totalToProcess,
                song.title,
                index + scannedIds.size,
                etrSeconds
            )

            try {
                val embedding = processSong(song)
                dao.insert(EmbeddingEntity(song.id, embedding.toList()))
            } catch (e: Exception) {
                Log.e("AiScanner", "Error processing song ${song.title}", e)
            }
        }
        
        if (!isStopping) {
            onProgress(1f, "Complete", songs.size, 0L)
        }
    }

    private fun processSong(song: Song): FloatArray {
        // 1. Decode 10 seconds of audio
        val audioData = decodeAudioChunk(song, 10000)
        if (audioData.isEmpty()) return FloatArray(512) // Fallback

        // 2. Extract Mel Spectrogram features
        val features = audioProcessor.extractFeatures(audioData)
        
        // 3. Inference
        return runInference(features)
    }

    private fun runInference(features: FloatArray): FloatArray {
        val session = ortSession ?: return FloatArray(512)
        val env = ortEnv ?: return FloatArray(512)
        
        try {
            // CLAP expects [batch, 1, frames, mels] -> [1, 1, 1001, 64]
            // features is already flattened [1001 * 64]
            val shape = longArrayOf(1, 1, 1001, 64)
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape)
            
            val output = session.run(mapOf("input_features" to tensor))
            @Suppress("UNCHECKED_CAST")
            val result = output.get(0).value as Array<FloatArray>
            
            // audio_embeds is [1, 512]
            return normalize(result[0])
        } catch (e: Exception) {
            Log.e("AiScanner", "Inference failed", e)
            return FloatArray(512)
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    private fun decodeAudioChunk(song: Song, durationMs: Long): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(song.path)
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) return FloatArray(0)
            
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val duration = format.getLong(MediaFormat.KEY_DURATION)
            val startTimeUs = max(0L, (duration / 2) - (durationMs * 1000 / 2))
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            val info = MediaCodec.BufferInfo()
            val decodedData = mutableListOf<Float>()
            val sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            
            var isEOS = false
            val timeoutUs = 10000L
            
            while (!isEOS) {
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                        if (extractor.sampleTime > startTimeUs + durationMs * 1000) isEOS = true
                    }
                }
                
                val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    val shortBuffer = outputBuffer.asShortBuffer()
                    
                    // Basic mono mixdown and normalization
                    while (shortBuffer.hasRemaining()) {
                        var sum = 0.0f
                        for (ch in 0 until channelCount) {
                            if (shortBuffer.hasRemaining()) sum += shortBuffer.get().toFloat() / 32768f
                        }
                        decodedData.add(sum / channelCount)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
            
            codec.stop()
            codec.release()
            extractor.release()
            
            return resample(decodedData.toFloatArray(), sourceSampleRate, 48000)
        } catch (e: Exception) {
            Log.e("AiScanner", "Decoding failed for ${song.title}", e)
            return FloatArray(0)
        }
    }

    private fun resample(data: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return data
        val ratio = from.toDouble() / to.toDouble()
        val newSize = (data.size / ratio).toInt()
        val resampled = FloatArray(newSize)
        for (i in 0 until newSize) {
            val oldPos = i * ratio
            val index = oldPos.toInt()
            val fraction = (oldPos - index).toFloat()
            if (index + 1 < data.size) {
                resampled[i] = data[index] * (1 - fraction) + data[index + 1] * fraction
            } else {
                resampled[i] = data[index]
            }
        }
        return resampled
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        return -1
    }

    suspend fun getStoredEmbeddings(): Map<Long, List<Float>> = withContext(Dispatchers.IO) {
        db.embeddingDao().getAll().associate { it.songId to it.vector }
    }

    suspend fun clearDatabase() = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }
}
