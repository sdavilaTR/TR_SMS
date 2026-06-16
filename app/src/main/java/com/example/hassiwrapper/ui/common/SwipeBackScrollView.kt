package com.example.hassiwrapper.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs

/**
 * ScrollView that detects an edge swipe (start within [EDGE_WIDTH_DP] of the left
 * border, dragged right) and invokes [onSwipeBack]. Used to navigate back on detail screens.
 */
class SwipeBackScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var onSwipeBack: (() -> Unit)? = null

    private val edgeWidthPx = EDGE_WIDTH_DP * resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var startX = 0f
    private var startY = 0f
    private var trackingEdge = false
    private var intercepted = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                trackingEdge = ev.x <= edgeWidthPx
                intercepted = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackingEdge && !intercepted) {
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    if (dx > touchSlop && dx > abs(dy)) {
                        intercepted = true
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (intercepted) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                val dx = ev.x - startX
                if (dx > width * SWIPE_THRESHOLD_FRACTION) {
                    onSwipeBack?.invoke()
                }
                intercepted = false
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    companion object {
        private const val EDGE_WIDTH_DP = 32
        private const val SWIPE_THRESHOLD_FRACTION = 0.2f
    }
}
