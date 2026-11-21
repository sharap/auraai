package music.ai.recommend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun BarVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    val barCount = 16
    val magnitudes = remember { mutableStateListOf(*Array(barCount) { 0.1f }) }

    if (isPlaying) {
        LaunchedEffect(Unit) {
            while (true) {
                for (i in 0 until barCount) {
                    magnitudes[i] = Random.nextFloat().coerceIn(0.1f, 1.0f)
                }
                delay(100)
            }
        }
    } else {
        LaunchedEffect(Unit) {
            for (i in 0 until barCount) {
                magnitudes[i] = 0.1f
            }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        magnitudes.forEach { magnitude ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(magnitude)
                    .background(barColor)
            )
        }
    }
}
