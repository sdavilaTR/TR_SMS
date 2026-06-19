package com.example.hassiwrapper.ui.common

import android.content.Context
import android.util.AttributeSet
import androidx.customview.widget.ViewDragHelper
import androidx.drawerlayout.widget.DrawerLayout

/**
 * DrawerLayout whose left-edge swipe-to-open zone is widened to a fraction of the
 * screen width. On API 29+, DrawerLayout resets the drag edge size to the system
 * gesture inset on every layout pass, so the wider size is re-applied after super.onLayout.
 */
class WideEdgeDrawerLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : DrawerLayout(context, attrs, defStyleAttr) {

    private val edgeSizePx = (resources.displayMetrics.widthPixels * EDGE_FRACTION).toInt()

    private val leftDragger: ViewDragHelper? by lazy {
        try {
            val field = DrawerLayout::class.java.getDeclaredField("mLeftDragger")
            field.isAccessible = true
            field.get(this) as? ViewDragHelper
        } catch (e: Exception) {
            null
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        leftDragger?.setEdgeSize(edgeSizePx)
    }

    companion object {
        private const val EDGE_FRACTION = 0.30
    }
}
