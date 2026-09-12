package music.ai.recommend.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import music.ai.recommend.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * A weight file the AI engine needs. [assetName] is the name used both inside `assets/` (when the
 * file is bundled into the APK) and inside the app's private `files/models/` directory (when it has
 * to be fetched over the network instead).
 */
enum class ModelAsset(
    val assetName: String,
    val remotePath: String,
    val sizeBytes: Long,
    /** LFS sha256 from the Hub; null for small files that the Hub tracks as plain git blobs. */
    val sha256: String?
) {
    /** int8. The default: a quarter of the download and markedly faster inference. */
    AUDIO_MODEL_QUANTIZED(
        assetName = "audio_model_quantized.onnx",
        remotePath = "onnx/audio_model_quantized.onnx",
        sizeBytes = 78_155_433L,
        sha256 = "021dd4fb962b4ed20cc3a6730b09e0ccf9f9c49931047032118a3b64828513a6"
    ),

    /** fp32. Higher fidelity embeddings, 281 MB, and the variant bundled in a full APK. */
    AUDIO_MODEL(
        assetName = "audio_model.onnx",
        remotePath = "onnx/audio_model.onnx",
        sizeBytes = 281_749_092L,
        sha256 = "3ecc72d27740e2a09ced20cf22fd6244122e5e506008763a0f368b3b4ff6eac8"
    ),
    TEXT_MODEL(
        assetName = "text_model.onnx",
        remotePath = "onnx/text_model_quantized.onnx",
        sizeBytes = 126_603_262L,
        sha256 = "8f9f29c5f6adee917553d4b3a70729c731c0d18b88efca0ae67c1a1fc278f3b6"
    ),
    VOCAB(
        assetName = "vocab.json",
        remotePath = "vocab.json",
        sizeBytes = 798_293L,
        sha256 = null
    ),
    MERGES(
        assetName = "merges.txt",
        remotePath = "merges.txt",
        sizeBytes = 456_318L,
        sha256 = null
    );

    companion object {
        /** Everything the text encoder needs. */
        val forText = listOf(TEXT_MODEL, VOCAB, MERGES)
    }
}

/**
 * Which export of the CLAP audio tower to analyse with.
 *
 * The two are not interchangeable: an int8 model and an fp32 model produce different embeddings for
 * the same track, so a library analysed with one should be re-analysed after switching. The choice
 * is recorded alongside the embeddings so the UI can say so.
 */
enum class AudioModelVariant(val asset: ModelAsset) {
    QUANTIZED(ModelAsset.AUDIO_MODEL_QUANTIZED),
    FULL(ModelAsset.AUDIO_MODEL);

    /** Everything the scanner needs for this variant. */
    val assets: List<ModelAsset> get() = listOf(asset)
}

/** Progress of an in-flight [ModelRepository.ensure] call. */
data class ModelProgress(
    val running: Boolean = false,
    val currentFile: String? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val error: String? = null
) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/**
 * Resolves CLAP weights to a readable on-disk file, pulling them from Hugging Face when they were
 * not bundled into the APK.
 *
 * Resolution order per file: APK assets, then `files/models/`, then download. Downloads land in a
 * `.part` file and are only renamed into place once the length (and, when known, the sha256)
 * matches, so a killed process or a full disk can never leave a truncated model behind; a restarted
 * download resumes the `.part` with a Range request.
 */
class ModelRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: ModelRepository? = null

        /**
         * One instance per process. The scan service downloads while the settings screen watches
         * [progress]; two instances would mean two progress flows and two concurrent downloads.
         */
        fun getInstance(context: Context): ModelRepository =
            instance ?: synchronized(this) {
                instance ?: ModelRepository(context.applicationContext).also { instance = it }
            }

        private const val KEY_SELECTED_VARIANT = "audio_variant"
        private const val KEY_EMBEDDINGS_VARIANT = "embeddings_audio_variant"
        private const val TAG = "ModelRepository"
        private const val HF_ENDPOINT = "https://huggingface.co"
        private const val DEFAULT_BUFFER = 1 shl 16
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val SPACE_HEADROOM = 32L * 1024 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }


    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, "models")
    private val mutex = Mutex()

    private val _progress = MutableStateFlow(ModelProgress())
    val progress: StateFlow<ModelProgress> = _progress.asStateFlow()

    // Whether an asset ships in the APK cannot change while the process lives, and the status row
    // in settings asks repeatedly, so the answer is cached rather than opening the asset each time.
    private val bundledCache = ConcurrentHashMap<ModelAsset, Boolean>()

    /** True when the file ships inside the APK and needs neither download nor copy. */
    fun isBundled(asset: ModelAsset): Boolean = bundledCache.getOrPut(asset) {
        try {
            appContext.assets.open(asset.assetName).close()
            true
        } catch (e: IOException) {
            false
        }
    }

    private val prefs = appContext.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)

    private val _audioVariant = MutableStateFlow(loadSelectedVariant())

    /** The export the scanner analyses with. */
    val audioVariant: StateFlow<AudioModelVariant> = _audioVariant.asStateFlow()

    private val _embeddingsVariant = MutableStateFlow(loadStoredVariant(KEY_EMBEDDINGS_VARIANT))

    /**
     * The variant that produced the embeddings already in the database, or null when nothing has
     * been analysed. Differs from [audioVariant] only after the user switches, which is exactly when
     * the UI should say a re-analysis is due.
     */
    val embeddingsVariant: StateFlow<AudioModelVariant?> = _embeddingsVariant.asStateFlow()

    fun selectAudioVariant(variant: AudioModelVariant) {
        if (_audioVariant.value == variant) return
        prefs.edit().putString(KEY_SELECTED_VARIANT, variant.name).apply()
        _audioVariant.value = variant
    }

    /** Called by the scanner once it has actually analysed something with [variant]. */
    fun recordEmbeddingsVariant(variant: AudioModelVariant) {
        if (_embeddingsVariant.value == variant) return
        prefs.edit().putString(KEY_EMBEDDINGS_VARIANT, variant.name).apply()
        _embeddingsVariant.value = variant
    }

    fun forgetEmbeddingsVariant() {
        prefs.edit().remove(KEY_EMBEDDINGS_VARIANT).apply()
        _embeddingsVariant.value = null
    }

    private fun loadSelectedVariant(): AudioModelVariant {
        loadStoredVariant(KEY_SELECTED_VARIANT)?.let { return it }
        // Nothing chosen yet. Quantized is the intended default, but when the full model is already
        // there — bundled in the APK, or downloaded by an older version of the app — switching to
        // quantized would mean fetching 78 MB to replace a better model that costs nothing.
        return if (isAvailable(ModelAsset.AUDIO_MODEL)) AudioModelVariant.FULL else AudioModelVariant.QUANTIZED
    }

    private fun loadStoredVariant(key: String): AudioModelVariant? {
        val name = prefs.getString(key, null) ?: return null
        return runCatching { AudioModelVariant.valueOf(name) }.getOrNull()
    }

    /** True when the file is already usable, whether it came from the APK or from the network. */
    fun isAvailable(asset: ModelAsset): Boolean =
        isBundled(asset) || localFile(asset).length() == asset.sizeBytes

    fun isAvailable(assets: List<ModelAsset>): Boolean = assets.all { isAvailable(it) }

    /** Bytes that [assets] would have to pull over the network right now. */
    fun pendingDownloadBytes(assets: List<ModelAsset>): Long =
        assets.filterNot { isAvailable(it) }.sumOf { it.sizeBytes }

    private fun localFile(asset: ModelAsset): File = File(modelsDir, asset.assetName)

    private fun partFile(asset: ModelAsset): File = File(modelsDir, asset.assetName + ".part")

    /**
     * Opens the file for streaming reads. Cheap for bundled assets — no copy involved — so this is
     * the right entry point for the tokenizer's vocab/merges.
     */
    fun openStream(asset: ModelAsset): InputStream =
        if (isBundled(asset)) appContext.assets.open(asset.assetName)
        else localFile(asset).inputStream()

    /**
     * Returns a real filesystem path for [asset], which is what ONNX Runtime needs in order to
     * memory-map the weights instead of holding them on the Java heap. A bundled asset is extracted
     * once into `files/models/`; compressed assets cannot be mapped in place.
     *
     * @return the file, or null if it is neither bundled nor downloaded.
     */
    suspend fun fileForInference(asset: ModelAsset): File? = withContext(Dispatchers.IO) {
        val target = localFile(asset)
        if (target.length() == asset.sizeBytes) return@withContext target
        if (!isBundled(asset)) return@withContext null

        modelsDir.mkdirs()
        val tmp = partFile(asset)
        try {
            appContext.assets.open(asset.assetName).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, DEFAULT_BUFFER) }
            }
            if (tmp.length() != asset.sizeBytes) {
                Log.w(TAG, "Bundled ${asset.assetName} is ${tmp.length()}B, expected ${asset.sizeBytes}B")
            }
            if (tmp.renameTo(target)) target else null
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract ${asset.assetName} from assets", e)
            tmp.delete()
            null
        }
    }

    /**
     * Makes sure every asset in [assets] is present, downloading the missing ones. Safe to call from
     * several places at once — concurrent callers queue on a mutex rather than downloading twice.
     *
     * @return true if all of them are now available.
     */
    suspend fun ensure(assets: List<ModelAsset>): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val missing = assets.filterNot { isAvailable(it) }
            if (missing.isEmpty()) {
                _progress.value = ModelProgress()
                return@withContext true
            }

            val total = missing.sumOf { it.sizeBytes }
            val alreadyOnDisk = missing.sumOf { partFile(it).length() }
            val free = modelsDir.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (free < total - alreadyOnDisk + SPACE_HEADROOM) {
                _progress.value = ModelProgress(error = "not_enough_space")
                return@withContext false
            }

            modelsDir.mkdirs()
            var done = 0L
            try {
                for (asset in missing) {
                    _progress.value = ModelProgress(
                        running = true,
                        currentFile = asset.assetName,
                        bytesDone = done,
                        bytesTotal = total
                    )
                    download(asset, total, done)
                    done += asset.sizeBytes
                }
                _progress.value = ModelProgress()
                true
            } catch (e: CancellationException) {
                _progress.value = ModelProgress()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
                _progress.value = ModelProgress(error = e.message ?: e.javaClass.simpleName)
                false
            }
        }
    }

    /** Deletes downloaded copies. Bundled assets are untouched — they live in the APK. */
    suspend fun deleteDownloaded(): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            var freed = 0L
            modelsDir.listFiles()?.forEach { file ->
                freed += file.length()
                file.delete()
            }
            freed
        }
    }

    private suspend fun download(asset: ModelAsset, grandTotal: Long, offsetInTotal: Long) {
        val part = partFile(asset)
        val target = localFile(asset)
        var existing = part.length()
        if (existing > asset.sizeBytes) {
            part.delete()
            existing = 0L
        }

        // A process that died between the last byte and the rename leaves a complete .part. Asking
        // for "bytes=<size>-" would come back 416, so verify and move it instead.
        if (existing == asset.sizeBytes) {
            finish(asset, part, target)
            return
        }

        val url = URL("$HF_ENDPOINT/${BuildConfig.HF_MODEL_REPO}/resolve/${BuildConfig.HF_MODEL_REVISION}/${asset.remotePath}")
        val connection = openConnection(url, existing)
        try {
            val code = connection.responseCode
            // A server that ignores Range answers 200 with the whole file; restart from zero.
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resuming) {
                throw IOException("HTTP $code for ${asset.remotePath}")
            }
            if (!resuming) existing = 0L

            RandomAccessFile(part, "rw").use { out ->
                out.setLength(existing)
                out.seek(existing)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    var written = existing
                    var lastPublish = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read

                        // Throttle: the flow drives a progress bar, not a byte counter.
                        val now = System.currentTimeMillis()
                        if (now - lastPublish >= PROGRESS_INTERVAL_MS) {
                            lastPublish = now
                            _progress.value = ModelProgress(
                                running = true,
                                currentFile = asset.assetName,
                                bytesDone = offsetInTotal + written,
                                bytesTotal = grandTotal
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        finish(asset, part, target)
    }

    /** Verifies the finished download and moves it into place; a bad file is deleted, not kept. */
    private fun finish(asset: ModelAsset, part: File, target: File) {
        if (part.length() != asset.sizeBytes) {
            part.delete()
            throw IOException("${asset.assetName}: got ${part.length()}B, expected ${asset.sizeBytes}B")
        }
        asset.sha256?.let { expected ->
            val actual = sha256Of(part)
            if (!actual.equals(expected, ignoreCase = true)) {
                part.delete()
                throw IOException("${asset.assetName}: checksum mismatch")
            }
        }
        if (!part.renameTo(target)) {
            throw IOException("Could not move ${asset.assetName} into place")
        }
    }

    private fun openConnection(url: URL, resumeFrom: Long): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                // HF redirects LFS objects to a CDN host; follow those by hand so the Range header
                // survives and so an http->https hop is not silently dropped.
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "AuraAI/${BuildConfig.VERSION_NAME}")
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            val code = connection.responseCode
            if (code !in REDIRECT_CODES) return connection

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location == null || ++redirects > MAX_REDIRECTS) {
                throw IOException("Too many redirects for $url")
            }
            current = URL(current, location)
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
