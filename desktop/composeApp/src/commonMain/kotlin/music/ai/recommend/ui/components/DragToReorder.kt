package music.ai.recommend.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Dragging rows of a [androidx.compose.foundation.lazy.LazyColumn] into a different order.
 *
 * Written rather than taken from a library because the one thing it has to be is predictable: a
 * reordering that lands a track one place from where it was let go is worse than none, and a
 * dependency pulled in for a gesture is a dependency to keep in step with Compose for ever. It is
 * about eighty lines, and the whole of it is the two sums below.
 *
 * The row is not moved. It is drawn offset from where it belongs while the finger holds it, and
 * every time the offset carries it past the middle of a neighbour, the list itself is reordered
 * underneath: [onMove] is called, the offset is reduced by the height of the row just passed, and
 * the finger goes on holding the same row at the same place on the screen. That is why the offset
 * is adjusted rather than reset — resetting it loses whatever part of the movement had not yet
 * been spent, and a slow drag then stalls while a fast one overshoots.
 *
 * The caller owns the list. This asks for a move and does not perform one, so the same gesture
 * drives a list held in a view model, in a local state, or nothing at all.
 */
class DragToReorder internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val indexOf: (key: Any) -> Int,
    private val move: (from: Int, to: Int) -> Unit,
    private val dropped: () -> Unit
) {

    /** Which row the finger is holding, as an index into the list as it stands now. */
    var dragging by mutableStateOf<Int?>(null)
        private set

    private var travelled by mutableFloatStateOf(0f)

    /** How far from its place the held row is drawn, in pixels. */
    fun offsetOf(index: Int): Float = if (dragging == index) travelled else 0f

    /**
     * @param key what the list calls this row — the track's id, not its place in the list.
     *
     * A place is the one thing this cannot be told at the start, because the whole gesture is
     * about changing it. Held by key and looked up on each gesture, so that a row the finger moved
     * a moment ago is still the row it is holding.
     */
    fun start(key: Any) {
        val index = indexOf(key)
        if (index < 0) return
        dragging = index
        travelled = 0f
    }

    fun stop() {
        if (dragging == null) return
        dragging = null
        travelled = 0f
        dropped()
    }

    fun drag(dy: Float) {
        val index = dragging ?: return
        val settled = Reorder.settle(index, travelled + dy) { at -> itemAt(at)?.size }
        settled.moveTo?.let { move(index, it) }
        travelled = settled.travelled
        dragging = settled.index
        follow(settled.index)
    }

    /**
     * Scrolls the list when the held row is near an edge, so that a track can be dragged further
     * than one screen.
     *
     * What is scrolled is added to the offset, because the row is drawn relative to the place the
     * list gives it and that place moves with the content: without this the row would slide out
     * from under the finger at exactly the moment the owner is trying to aim it.
     */
    private fun follow(index: Int) {
        val item = itemAt(index) ?: return
        val info = listState.layoutInfo
        val margin = item.size * 0.75f
        val top = item.offset + travelled
        val bottom = top + item.size
        val step = when {
            bottom > info.viewportEndOffset - margin -> (bottom - (info.viewportEndOffset - margin))
            top < info.viewportStartOffset + margin -> (top - (info.viewportStartOffset + margin))
            else -> 0f
        }
        if (step == 0f) return
        scope.launch { travelled += listState.scrollBy(step.coerceIn(-48f, 48f)) }
    }

    private fun itemAt(index: Int): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
}

/**
 * @param indexOf where the row with that key is in the list as it stands now, or a negative
 *   number when it is not in it at all.
 * @param onMove asked for when a row has been carried past a neighbour; the caller reorders its
 *   own list and nothing else.
 * @param onDropped called once when the finger is lifted, which is where a caller that saves the
 *   order does it — once for the gesture rather than once per step. Not a promise: a gesture can
 *   also end by having its row taken out from under it, and a caller that must not lose the
 *   result saves it when the owner leaves the screen too.
 */
@Composable
fun rememberDragToReorder(
    listState: LazyListState,
    indexOf: (key: Any) -> Int,
    onMove: (from: Int, to: Int) -> Unit,
    onDropped: () -> Unit = {}
): DragToReorder {
    val scope = rememberCoroutineScope()
    val lookup = rememberUpdatedState(indexOf)
    val move = rememberUpdatedState(onMove)
    val dropped = rememberUpdatedState(onDropped)
    return remember(listState, scope) {
        DragToReorder(
            listState = listState,
            scope = scope,
            indexOf = { key -> lookup.value(key) },
            move = { from, to -> move.value(from, to) },
            dropped = { dropped.value() }
        )
    }
}

/**
 * The handle. The gesture starts here and not on the row, so that a list can still be scrolled
 * with a finger anywhere else on it — a drag that begins on the row itself has to wait for a long
 * press to tell the two apart, and that wait is felt.
 *
 * [key] is what identifies the row, and it must not be its place in the list. Keying this on the
 * index is the mistake that cost an afternoon: the keys of a `pointerInput` are what decides
 * whether the gesture it is running survives, so the first time the row moved, its index changed,
 * the block was torn down and started again, and the drag was cancelled mid-air. Cancelling it
 * that way calls nothing back — not even the cancel — so the finger went on moving over a row that
 * was no longer listening, nothing was saved when it was lifted, and the list quietly went back to
 * how it had been as soon as the screen next read it.
 */
fun Modifier.reorderHandle(state: DragToReorder, key: Any): Modifier =
    this.pointerInput(state, key) {
        detectVerticalDragGestures(
            onDragStart = { state.start(key) },
            onDragEnd = { state.stop() },
            onDragCancel = { state.stop() },
            onVerticalDrag = { change, dy ->
                change.consume()
                state.drag(dy)
            }
        )
    }

/**
 * Where a row ends up, as arithmetic and nothing else.
 *
 * Separated from the gesture because it is the one part that can be wrong in a way nobody sees
 * until it happens, and it runs on the thread drawing the screen. A pure function can have the
 * awkward cases written down in a test instead of hunted for with a finger.
 *
 * Two rules, and both were arrived at the hard way:
 *
 * **One step per event.** What the list says about its rows is a snapshot taken before this move,
 * so a second step in the same event would be measured against rows that have already moved. A
 * flick is therefore walked over several frames instead — the row stays under the finger the whole
 * time, and the ones around it catch up a frame or two later, which nobody can see.
 *
 * **The two rows' middles have to cross**, not their edges. An edge rule is not symmetric when the
 * rows are of different heights: a short row dragged a little past a tall one's edge would swap
 * with it, which then puts the short one far enough above the tall one's edge to swap straight
 * back, and so on for as long as the finger is still. With middles, moving at all requires more
 * travel than moving back can ever leave over, so it cannot happen at any pair of heights.
 */
internal object Reorder {

    /**
     * @param index where the held row now belongs.
     * @param travelled what is left of the movement, which is how far from that place it is drawn.
     * @param moveTo the place to move it to in the list, or null when it has not gone far enough.
     */
    data class Settled(val index: Int, val travelled: Float, val moveTo: Int?)

    /**
     * @param heightAt the height of the row at an index, or null where there is no such row — the
     *   ends of the list, and anything scrolled out of sight.
     */
    fun settle(from: Int, travelled: Float, heightAt: (Int) -> Int?): Settled {
        val standingStill = Settled(from, travelled, null)
        val own = heightAt(from) ?: return standingStill
        val downwards = travelled > 0
        val next = if (downwards) from + 1 else from - 1
        val height = heightAt(next) ?: return standingStill
        if (kotlin.math.abs(travelled) <= (own + height) / 2f) return standingStill
        // Taking the neighbour's place moves the row's own place by the neighbour's height, so
        // what is left to travel is reduced by exactly that and the row does not jump under the
        // finger.
        return Settled(
            index = next,
            travelled = travelled + if (downwards) -height.toFloat() else height.toFloat(),
            moveTo = next
        )
    }
}
