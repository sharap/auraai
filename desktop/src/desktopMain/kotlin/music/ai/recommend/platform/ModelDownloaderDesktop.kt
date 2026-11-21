package music.ai.recommend.platform

import kotlinx.coroutines.flow.StateFlow

actual class ModelDownloader actual constructor() {
    private val repository = ModelRepository.instance

    actual val progress: StateFlow<ModelProgress> get() = repository.progress

    actual fun audioModelReady(): Boolean = repository.isAvailable(ModelAsset.AUDIO_MODEL)

    actual fun audioModelSha256(): String = ModelAsset.AUDIO_MODEL.sha256
    actual fun textModelReady(): Boolean = repository.isAvailable(ModelAsset.TEXT_MODEL)

    actual fun russianModelReady(): Boolean = repository.isAvailable(ModelAsset.forRussianText)

    // Named lists rather than every asset there is: with the Russian encoder in the enum, asking
    // for `entries` would quietly put its 32 MB into the download the settings screen offers to
    // everyone, and "download the weights" would mean something different than it says.
    actual fun pendingBytes(): Long = repository.pendingDownloadBytes(ModelAsset.forClap)

    actual fun russianPendingBytes(): Long = repository.pendingDownloadBytes(ModelAsset.forRussianText)

    actual suspend fun ensureAll(): Boolean = repository.ensure(ModelAsset.forClap)

    actual suspend fun ensureRussian(): Boolean = repository.ensure(ModelAsset.forRussianText)

    actual suspend fun deleteDownloaded(): Long = repository.deleteDownloaded()
}
