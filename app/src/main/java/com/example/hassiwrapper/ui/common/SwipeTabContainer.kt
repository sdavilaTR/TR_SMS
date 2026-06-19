package com.example.hassiwrapper.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * FrameLayout that detects horizontal swipes and invokes [onSwipeLeft] / [onSwipeRight].
 * Used to switch between tabs by swiping over the tab content.
 */
class SwipeTabContainer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Swipe towards the start (finger moves left) -> go to next tab. */
    var onSwipeLeft: (() -> Unit)? = null

    /** Swipe towards the end (finger moves right) -> go to previous tab. */
    var onSwipeRight: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var startX = 0f
    private var startY = 0f
    private var intercepted = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                intercepted = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!intercepted) {
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
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
                val threshold = width * SWIPE_THRESHOLD_FRACTION
                when {
                    dx <= -threshold -> onSwipeLeft?.invoke()
                    dx >= threshold -> onSwipeRight?.invoke()
                }
                intercepted = false
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    companion object {
        private const val SWIPE_THRESHOLD_FRACTION = 0.2f
    }
}
