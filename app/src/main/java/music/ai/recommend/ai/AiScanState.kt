package music.ai.recommend.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Progress of the library scan, shared between the service that does the work and whatever UI
 * happens to be alive.
 *
 * Process-scoped on purpose: the scan outlives the Activity and the ViewModel, so the state cannot
 * live in either. The service is the only writer.
 */
object AiScanState {

    private val _stage = MutableStateFlow<ScanStage>(ScanStage.Idle)
    val stage: StateFlow<ScanStage> = _stage.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal fun setStage(stage: ScanStage) {
        _stage.value = stage
    }

    internal fun setRunning(running: Boolean) {
        _running.value = running
    }
}
