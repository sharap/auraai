package music.ai.recommend.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Android ships its own ModelRepository in the AuraAI app; nothing to do from here. */
actual class ModelDownloader actual constructor() {
    actual val progress: StateFlow<ModelProgress> = MutableStateFlow(ModelProgress())
    actual fun audioModelReady(): Boolean = true
    actual fun textModelReady(): Boolean = true
    actual fun pendingBytes(): Long = 0L
    actual suspend fun ensureAll(): Boolean = true
    actual suspend fun deleteDownloaded(): Long = 0L
}
