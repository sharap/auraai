package music.ai.recommend.platform

import kotlinx.coroutines.flow.StateFlow

actual class ModelDownloader actual constructor() {
    private val repository = ModelRepository.instance

    actual val progress: StateFlow<ModelProgress> get() = repository.progress

    actual fun audioModelReady(): Boolean = repository.isAvailable(ModelAsset.AUDIO_MODEL)

    actual fun audioModelSha256(): String = ModelAsset.AUDIO_MODEL.sha256
    actual fun textModelReady(): Boolean = repository.isAvailable(ModelAsset.TEXT_MODEL)

    actual fun pendingBytes(): Long = repository.pendingDownloadBytes(ModelAsset.entries.toList())

    actual suspend fun ensureAll(): Boolean = repository.ensure(ModelAsset.entries.toList())

    actual suspend fun deleteDownloaded(): Long = repository.deleteDownloaded()
}
