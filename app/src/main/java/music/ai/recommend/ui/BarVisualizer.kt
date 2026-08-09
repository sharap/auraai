package music.ai.recommend.ui

import android.media.audiofx.Visualizer
import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun BarVisualizer(
    audioSessionId: Int?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    var magnitudes by remember { mutableStateOf(FloatArray(32) { 0f }) }

    DisposableEffect(audioSessionId) {
        if (audioSessionId == null || audioSessionId <= 0) return@DisposableEffect onDispose {}
        
        var visualizer: Visualizer? = null
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0] 
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || !isPlaying) return
                        
                        val newMagnitudes = FloatArray(32)
                        var maxFrameMag = 0f
                        
                        // Extract magnitudes from FFT
                        for (i in 0 until 32) {
                            val index = (i + 1) * 2
                            if (index + 1 >= fft.size) break
                            val real = fft[index].toInt()
                            val imag = fft[index + 1].toInt()
                            val mag = Math.sqrt((real * real + imag * imag).toDouble()).toFloat()
                            newMagnitudes[i] = mag
                            if (mag > maxFrameMag) maxFrameMag = mag
                        }
                        
                        // Smoothing and normalization
                        val smoothed = FloatArray(32)
                        for (i in 0 until 32) {
                            val normalized = if (maxFrameMag > 0) (newMagnitudes[i] / maxFrameMag) else 0f
                            smoothed[i] = magnitudes[i] * 0.6f + normalized * 0.4f
                        }
                        magnitudes = smoothed
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) {
            Log.e("BarVisualizer", "Error initializing visualizer", e)
        }

        onDispose {
            visualizer?.enabled = false
            visualizer?.release()
        }
    }

    Spacer(
        modifier = modifier.drawWithCache {
            val barCount = magnitudes.size
            val barWidth = size.width / barCount
            val spacing = 2.dp.toPx()
            val actualBarWidth = (barWidth - spacing).coerceAtLeast(1f)
            
            onDrawBehind {
                magnitudes.forEachIndexed { index, magnitude ->
                    val barHeight = (magnitude * size.height * 0.8f).coerceAtLeast(4.dp.toPx())
                    drawRect(
                        color = barColor,
                        topLeft = Offset(
                            x = index * barWidth + spacing / 2,
                            y = size.height - barHeight
                        ),
                        size = Size(
                            width = actualBarWidth,
                            height = barHeight
                        )
                    )
                }
            }
        }
    )
}
