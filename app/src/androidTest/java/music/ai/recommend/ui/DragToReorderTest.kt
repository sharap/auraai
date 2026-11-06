package music.ai.recommend.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The gesture itself, on a device, against a real list.
 *
 * [Reorder] has the arithmetic under test on a desktop JVM. What that cannot reach is the part that
 * was actually broken: whether the gesture *survives* the list being reordered underneath it. The
 * handle's `pointerInput` was keyed on the row's index, and the keys of a `pointerInput` are what
 * decides whether the block running the gesture lives or is torn down and started again — so the
 * first time the row moved, the drag was cancelled in mid-air. Cancelling it that way calls nothing
 * back, not even the cancel, so the finger went on moving over a row that was no longer listening
 * and nothing was saved when it was lifted. The list looked right until the screen next read it
 * from the playlist, and then went back to how it had been.
 *
 * Which is why the assertions below are about the *second* move and about the drop. One move and no
 * drop is exactly what the broken version did.
 *
 * Run these on their own, not in the same `am instrument` run as `MusicViewModelStartsTest`: that
 * one builds a real view model, whose coroutines go on running afterwards — the fifteen-second
 * foreground poll among them — and a Compose test sharing the process then waits for an idle main
 * thread that never comes.
 */
class DragToReorderTest {

    @get:Rule
    val compose = createComposeRule()

    private val rowHeight = 100.dp

    private var order = listOf("а", "б", "в", "г")
    private var moves = 0
    private var drops = 0
    private var saved: List<String>? = null

    private fun list() {
        compose.setContent {
            val listState = rememberLazyListState()
            var shown by remember { mutableStateOf(order) }
            val reorder = rememberDragToReorder(
                listState = listState,
                indexOf = { key -> shown.indexOfFirst { it == key } },
                onMove = { from, to ->
                    shown = shown.toMutableList().apply { add(to, removeAt(from)) }
                    order = shown
                    moves++
                },
                onDropped = {
                    drops++
                    saved = shown
                }
            )
            LazyColumn(state = listState, modifier = Modifier.height(rowHeight * 4)) {
                itemsIndexed(shown, key = { _, item -> item }) { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .graphicsLayer { translationY = reorder.offsetOf(index) }
                    ) {
                        Text(item)
                        Box(
                            modifier = Modifier
                                .size(rowHeight)
                                .testTag("ручка-$item")
                                .reorderHandle(reorder, item)
                        )
                    }
                }
            }
        }
    }

    /** A row's height in pixels, which is what a drag has to exceed to move anything. */
    private val step: Float get() = compose.density.run { rowHeight.toPx() } * 1.2f

    private fun handle(item: String) = compose.onNodeWithTag("ручка-$item")

    @Test
    fun aRowDraggedTwiceOverItsNeighboursEndsUpTwoPlacesDownAndIsSaved() {
        list()
        handle("а").performTouchInput { down(center) }
        // Separate calls on purpose: each one waits for the list to settle first, so the list is
        // really reordered between the two movements — which is the condition the gesture used to
        // die on.
        handle("а").performTouchInput { moveBy(Offset(0f, step)) }
        handle("а").performTouchInput { moveBy(Offset(0f, step)) }
        handle("а").performTouchInput { up() }

        compose.waitForIdle()
        assertEquals(2, moves)
        assertEquals(listOf("б", "в", "а", "г"), order)
        // Lifted once, saved once, and what was saved is what is on the screen.
        assertEquals(1, drops)
        assertEquals(order, saved)
    }

    @Test
    fun aRowDraggedUpwardsGoesUp() {
        list()
        handle("в").performTouchInput { down(center) }
        handle("в").performTouchInput { moveBy(Offset(0f, -step)) }
        handle("в").performTouchInput { up() }

        compose.waitForIdle()
        assertEquals(listOf("а", "в", "б", "г"), order)
        assertEquals(1, drops)
    }

    @Test
    fun aDragTooShortToPassAnythingChangesNothingAndStillReportsTheDrop() {
        list()
        handle("а").performTouchInput { down(center) }
        handle("а").performTouchInput { moveBy(Offset(0f, step / 4f)) }
        handle("а").performTouchInput { up() }

        compose.waitForIdle()
        assertEquals(0, moves)
        assertEquals(listOf("а", "б", "в", "г"), order)
        // Reported anyway, because the caller decides what to do about an order that has not
        // changed — and the one in the app answers by not recording it.
        assertEquals(1, drops)
        assertEquals(listOf("а", "б", "в", "г"), saved)
    }
}
