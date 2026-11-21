package music.ai.recommend.platform

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
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A weight file the AI engine needs.
 *
 * Only the two ONNX exports are listed: they are hundreds of megabytes and cannot live in a git
 * repository, while the tokenizer's vocab.json and merges.txt are about a megabyte together and
 * ship with the source.
 *
 * The sizes and checksums are those of the files this app was developed against, verified byte for
 * byte, so a download that differs from them is a download that would produce different embeddings.
 */
enum class ModelAsset(
    val fileName: String,
    val remotePath: String,
    val sizeBytes: Long,
    val sha256: String
) {
    /** fp32 audio tower. The embeddings in the library were produced by exactly this file. */
    AUDIO_MODEL(
        fileName = "audio_model.onnx",
        remotePath = "onnx/audio_model.onnx",
        sizeBytes = 281_749_092L,
        sha256 = "3ecc72d27740e2a09ced20cf22fd6244122e5e506008763a0f368b3b4ff6eac8"
    ),

    /** int8 text tower: a quarter of the size, and text embeddings are not what gets stored. */
    TEXT_MODEL(
        fileName = "text_model.onnx",
        remotePath = "onnx/text_model_quantized.onnx",
        sizeBytes = 126_603_262L,
        sha256 = "8f9f29c5f6adee917553d4b3a70729c731c0d18b88efca0ae67c1a1fc278f3b6"
    )
}

/**
 * Resolves CLAP weights to a readable on-disk file, pulling them from Hugging Face on first use.
 *
 * The weights are not distributed with the source: 408 MB of them would not fit in a git repository
 * and are not this project's to redistribute. They land in `~/.aimusic/models/` once and stay.
 *
 * A download goes to a `.part` file and is only renamed into place once the length and the sha256
 * both match, so a killed process or a full disk can never leave a truncated model behind; a
 * restarted download resumes the `.part` with a Range request.
 */
class ModelRepository private constructor() {

    private val modelsDir = File(System.getProperty("user.home"), ".aimusic/models")
    private val mutex = Mutex()

    private val _progress = MutableStateFlow(ModelProgress())
    val progress: StateFlow<ModelProgress> = _progress.asStateFlow()

    fun isAvailable(asset: ModelAsset): Boolean = localFile(asset).length() == asset.sizeBytes

    fun isAvailable(assets: List<ModelAsset>): Boolean = assets.all { isAvailable(it) }

    /** Bytes that [assets] would have to pull over the network right now. */
    fun pendingDownloadBytes(assets: List<ModelAsset>): Long =
        assets.filterNot { isAvailable(it) }.sumOf { it.sizeBytes }

    fun localFile(asset: ModelAsset): File = File(modelsDir, asset.fileName)

    private fun partFile(asset: ModelAsset): File = File(modelsDir, asset.fileName + ".part")

    /**
     * Makes sure every asset in [assets] is present, downloading the missing ones. Safe to call
     * from several places at once — concurrent callers queue on a mutex rather than downloading
     * twice.
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
            val free = runCatching { modelsDir.parentFile?.usableSpace }.getOrNull() ?: Long.MAX_VALUE
            if (free < total - alreadyOnDisk + SPACE_HEADROOM) {
                _progress.value = ModelProgress(error = "not enough free space")
                return@withContext false
            }

            modelsDir.mkdirs()
            var done = 0L
            try {
                for (asset in missing) {
                    _progress.value = ModelProgress(
                        running = true,
                        currentFile = asset.fileName,
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
                println("ModelRepository: download failed: ${e.message}")
                _progress.value = ModelProgress(error = e.message ?: e.javaClass.simpleName)
                false
            }
        }
    }

    /** Deletes the downloaded weights, so they can be fetched again. */
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

        val url = URL("$HF_ENDPOINT/$HF_REPO/resolve/$HF_REVISION/${asset.remotePath}")
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

                        // Throttled: the flow drives a progress bar, not a byte counter.
                        val now = System.currentTimeMillis()
                        if (now - lastPublish >= PROGRESS_INTERVAL_MS) {
                            lastPublish = now
                            _progress.value = ModelProgress(
                                running = true,
                                currentFile = asset.fileName,
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
            throw IOException("${asset.fileName}: got ${part.length()}B, expected ${asset.sizeBytes}B")
        }
        val actual = sha256Of(part)
        if (!actual.equals(asset.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("${asset.fileName}: checksum mismatch")
        }
        if (!part.renameTo(target)) {
            throw IOException("Could not move ${asset.fileName} into place")
        }
    }

    /**
     * Teaches the JVM about a proxy set the way Linux sets one.
     *
     * HttpURLConnection reads the `https.proxyHost` system properties and ignores the
     * `HTTPS_PROXY` environment variable that every command-line tool honours, so on a machine
     * that reaches the network only through a proxy the download fails with "Network is
     * unreachable" while curl on the same machine works. An explicitly configured system property
     * always wins, so passing -Dhttps.proxyHost still overrides this.
     */
    private fun configureProxyFromEnvironment() {
        for (scheme in listOf("https", "http")) {
            if (System.getProperty("$scheme.proxyHost") != null) continue
            val value = System.getenv("${scheme.uppercase()}_PROXY")
                ?: System.getenv("${scheme}_proxy")
                ?: System.getenv("ALL_PROXY")
                ?: System.getenv("all_proxy")
                ?: continue
            val parsed = runCatching { URL(if ("://" in value) value else "http://$value") }.getOrNull() ?: continue
            val port = if (parsed.port > 0) parsed.port else parsed.defaultPort
            if (parsed.host.isNullOrEmpty() || port <= 0) continue
            System.setProperty("$scheme.proxyHost", parsed.host)
            System.setProperty("$scheme.proxyPort", port.toString())
            println("ModelRepository: using the $scheme proxy from the environment (${parsed.host}:$port)")
        }
    }

    private fun openConnection(url: URL, resumeFrom: Long): HttpURLConnection {
        configureProxyFromEnvironment()
        var current = url
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                // The Hub redirects LFS objects to a CDN host; those are followed by hand so the
                // Range header survives and so an http->https hop is not silently dropped.
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "AiMusic")
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

    companion object {
        /**
         * One instance per process: the scanner downloads while the settings screen watches
         * [progress], and two instances would mean two progress flows and two concurrent downloads.
         */
        val instance: ModelRepository by lazy { ModelRepository() }

        /** Overridable so a build can point at a mirror rather than the Hub. */
        private val HF_ENDPOINT: String = System.getProperty("aimusic.hf.endpoint") ?: "https://huggingface.co"
        private val HF_REPO: String = System.getProperty("aimusic.hf.repo") ?: "Xenova/larger_clap_music_and_speech"
        private val HF_REVISION: String = System.getProperty("aimusic.hf.revision") ?: "main"

        private const val DEFAULT_BUFFER = 1 shl 16
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val SPACE_HEADROOM = 32L * 1024 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
