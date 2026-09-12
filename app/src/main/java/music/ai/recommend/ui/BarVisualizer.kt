package music.ai.recommend.ui

import android.media.audiofx.Visualizer
import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

private const val BAR_COUNT = 32

@Composable
fun BarVisualizer(
    audioSessionId: Int?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    // Written from the FFT callback ~10 times a second. neverEqualPolicy makes every write count
    // even though the array identity may repeat, and the value is read only inside onDrawBehind,
    // so an update invalidates the draw phase instead of recomposing the tree above it.
    val magnitudes = remember {
        mutableStateOf(FloatArray(BAR_COUNT), neverEqualPolicy())
    }

    // The capture callback outlives the composition that created it, so it has to read the current
    // isPlaying rather than the value captured when the effect started.
    val playing by rememberUpdatedState(isPlaying)

    DisposableEffect(audioSessionId) {
        if (audioSessionId == null || audioSessionId <= 0) return@DisposableEffect onDispose {}

        var visualizer: Visualizer? = null
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            if (fft == null) return
                            val previous = magnitudes.value
                            val next = FloatArray(BAR_COUNT)

                            if (!playing) {
                                // Decay to rest instead of freezing mid-spike.
                                for (i in 0 until BAR_COUNT) next[i] = previous[i] * 0.8f
                                magnitudes.value = next
                                return
                            }

                            var peak = 0f
                            for (i in 0 until BAR_COUNT) {
                                val index = (i + 1) * 2
                                if (index + 1 >= fft.size) break
                                val real = fft[index].toInt()
                                val imag = fft[index + 1].toInt()
                                val magnitude = sqrt((real * real + imag * imag).toFloat())
                                next[i] = magnitude
                                if (magnitude > peak) peak = magnitude
                            }
                            for (i in 0 until BAR_COUNT) {
                                val normalized = if (peak > 0f) next[i] / peak else 0f
                                next[i] = previous[i] * 0.6f + normalized * 0.4f
                            }
                            magnitudes.value = next
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true
                )
                enabled = true
            }
        } catch (e: Exception) {
            Log.e("BarVisualizer", "Error initializing visualizer", e)
        }

        onDispose {
            runCatching { visualizer?.enabled = false }
            runCatching { visualizer?.release() }
        }
    }

    Spacer(
        modifier = modifier.drawWithCache {
            val barWidth = size.width / BAR_COUNT
            val spacing = 2.dp.toPx()
            val actualBarWidth = (barWidth - spacing).coerceAtLeast(1f)
            val minHeight = 4.dp.toPx()

            onDrawBehind {
                val values = magnitudes.value
                for (index in 0 until BAR_COUNT) {
                    val barHeight = (values[index] * size.height * 0.8f).coerceAtLeast(minHeight)
                    drawRect(
                        color = barColor,
                        topLeft = Offset(x = index * barWidth + spacing / 2, y = size.height - barHeight),
                        size = Size(width = actualBarWidth, height = barHeight)
                    )
                }
            }
        }
    )
}
