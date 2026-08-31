package com.lagradost.cloudstream3.ui.livetv

import android.content.Context
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Zone based touch handler for the fullscreen Live TV gesture overlay.
 *
 * Gesture zones (phone only, mirrors the spec exactly):
 *  - Horizontal swipe anywhere on the player (except the right search-edge strip):
 *        swipe right -> previous channel, swipe left -> next channel
 *  - Vertical swipe on the RIGHT half of the screen: volume up/down
 *  - Vertical swipe on the LEFT half of the screen: brightness up/down
 *  - Horizontal swipe starting within [searchEdgeWidthPx] of the right edge, moving left:
 *        opens the search panel
 *  - Vertical swipe starting within [refreshZoneHeightPx] of the top, moving down:
 *        triggers a channel-list refresh
 */
class LiveTvGestureHelper(
    context: Context,
    private val onNextChannel: () -> Unit,
    private val onPreviousChannel: () -> Unit,
    private val onOpenSearch: () -> Unit,
    private val onRefresh: () -> Unit,
    /** value in [-1f, 1f], relative step, positive = increase */
    private val onVolumeStep: (Float) -> Unit,
    /** value in [-1f, 1f], relative step, positive = increase */
    private val onBrightnessStep: (Float) -> Unit,
    /** Long-press on the player surface triggers Picture-in-Picture. */
    private val onLongPress: () -> Unit = {},
) : View.OnTouchListener {

    private val searchEdgeWidthPx = dp(context, 32f)
    private val refreshZoneHeightPx = dp(context, 90f)
    private val touchSlopPx = dp(context, 14f)
    private val longPressTimeoutMs = 500L
    private val longPressSlopPx = dp(context, 20f)
    private val stepPx = dp(context, 16f)
    private val horizontalActionThresholdPx = dp(context, 60f)

    private var startX = 0f
    private var startY = 0f
    private var lastStepY = 0f
    private var axisLocked: Axis? = null
    private var startedInSearchEdge = false
    private var startedInRefreshZone = false
    private var searchOpenedThisGesture = false
    private var refreshTriggeredThisGesture = false
    private var startedOnRightHalf = false
    private var longPressFired = false
    private var longPressPending = false
    private var downTimeMs = 0L

    private enum class Axis { HORIZONTAL, VERTICAL }

    private fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastStepY = event.y
                axisLocked = null
                searchOpenedThisGesture = false
                refreshTriggeredThisGesture = false
                startedInSearchEdge = event.x >= v.width - searchEdgeWidthPx
                startedInRefreshZone = event.y <= refreshZoneHeightPx
                startedOnRightHalf = event.x >= v.width / 2f
                downTimeMs = event.eventTime
                longPressFired = false
                longPressPending = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY

                // Cancel long-press if finger moves too far.
                if (longPressPending && (abs(dx) > longPressSlopPx || abs(dy) > longPressSlopPx)) {
                    longPressPending = false
                }

                // Fire long-press if held long enough without moving.
                if (longPressPending && !longPressFired &&
                    event.eventTime - downTimeMs >= longPressTimeoutMs
                ) {
                    longPressFired = true
                    longPressPending = false
                    onLongPress()
                    return true
                }

                if (axisLocked == null) {
                    if (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx) {
                        axisLocked = if (abs(dx) > abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
                    }
                }

                when (axisLocked) {
                    Axis.VERTICAL -> {
                        if (startedInRefreshZone && !refreshTriggeredThisGesture) {
                            if (dy > refreshZoneHeightPx) {
                                refreshTriggeredThisGesture = true
                                onRefresh()
                            }
                            return true
                        }
                        // Continuous volume / brightness stepping.
                        val stepDelta = lastStepY - event.y // positive when moving up
                        if (abs(stepDelta) >= stepPx) {
                            val steps = (stepDelta / stepPx)
                            val normalized = (steps * 0.05f).coerceIn(-1f, 1f)
                            if (startedOnRightHalf) onVolumeStep(normalized) else onBrightnessStep(normalized)
                            lastStepY = event.y
                        }
                    }

                    Axis.HORIZONTAL -> {
                        if (startedInSearchEdge && dx < 0 && !searchOpenedThisGesture &&
                            abs(dx) > touchSlopPx
                        ) {
                            searchOpenedThisGesture = true
                            onOpenSearch()
                        }
                    }

                    else -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressPending = false
                if (longPressFired) {
                    longPressFired = false
                    return true
                }
                val dx = event.x - startX
                if (axisLocked == Axis.HORIZONTAL && !startedInSearchEdge &&
                    abs(dx) > horizontalActionThresholdPx
                ) {
                    // Swipe right -> previous channel, swipe left -> next channel (per spec).
                    if (dx > 0) onPreviousChannel() else onNextChannel()
                }
                axisLocked = null
                return true
            }
        }
        return false
    }
}
