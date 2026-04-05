package com.example.hassiwrapper.ui.scanner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Full-screen overlay that draws a semi-transparent mask with a rounded-square cutout
 * in the center, plus corner brackets for a modern scanner look.
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val overlayPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val windowSizeDp = 260f
    private val cornerRadiusDp = 24f
    private val bracketLenDp = 36f

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val windowSize = dp(windowSizeDp)
        val cornerRadius = dp(cornerRadiusDp)
        val bracketLen = dp(bracketLenDp)

        val left = (w - windowSize) / 2f
        val top = (h - windowSize) / 2f
        val right = left + windowSize
        val bottom = top + windowSize

        val windowRect = RectF(left, top, right, bottom)

        // Draw overlay with cutout
        val saved = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawRoundRect(windowRect, cornerRadius, cornerRadius, clearPaint)
        canvas.restoreToCount(saved)

        // Draw corner brackets
        borderPaint.strokeWidth = dp(3f)

        // Top-left
        drawCorner(canvas, left, top, bracketLen, cornerRadius, topLeft = true)
        // Top-right
        drawCorner(canvas, right, top, bracketLen, cornerRadius, topRight = true)
        // Bottom-left
        drawCorner(canvas, left, bottom, bracketLen, cornerRadius, bottomLeft = true)
        // Bottom-right
        drawCorner(canvas, right, bottom, bracketLen, cornerRadius, bottomRight = true)
    }

    private fun drawCorner(
        canvas: Canvas, cx: Float, cy: Float, len: Float, radius: Float,
        topLeft: Boolean = false, topRight: Boolean = false,
        bottomLeft: Boolean = false, bottomRight: Boolean = false
    ) {
        val path = Path()
        when {
            topLeft -> {
                path.moveTo(cx, cy + len)
                path.lineTo(cx, cy + radius)
                path.quadTo(cx, cy, cx + radius, cy)
                path.lineTo(cx + len, cy)
            }
            topRight -> {
                path.moveTo(cx - len, cy)
                path.lineTo(cx - radius, cy)
                path.quadTo(cx, cy, cx, cy + radius)
                path.lineTo(cx, cy + len)
            }
            bottomLeft -> {
                path.moveTo(cx, cy - len)
                path.lineTo(cx, cy - radius)
                path.quadTo(cx, cy, cx + radius, cy)
                path.lineTo(cx + len, cy)
            }
            bottomRight -> {
                path.moveTo(cx - len, cy)
                path.lineTo(cx - radius, cy)
                path.quadTo(cx, cy, cx, cy - radius)
                path.lineTo(cx, cy - len)
            }
        }
        canvas.drawPath(path, borderPaint)
    }
}
