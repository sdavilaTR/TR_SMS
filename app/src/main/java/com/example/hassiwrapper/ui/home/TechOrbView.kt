package com.example.hassiwrapper.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class TechOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var a1 = 45f
    private var a2 = 0f
    private var a3 = 45f

    private val pOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFFFFF.toInt()
    }
    private val pMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x44FFFFFF.toInt()
    }
    private val pInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xEEFFFFFF.toInt()
    }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val pCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xAAFFFFFF.toInt()
    }
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val dp = resources.displayMetrics.density
        pOuter.strokeWidth = 1.5f * dp
        pMid.strokeWidth = 1f * dp
        pInner.strokeWidth = 2f * dp
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        val dp = resources.displayMetrics.density

        a1 += 0.35f
        a2 -= 0.55f
        a3 += 1.0f

        // Outer diamond — slow CW
        drawDiamond(canvas, cx, cy, r * 0.92f, a1, pOuter)

        // Dots at tips of outer diamond
        for (i in 0..3) {
            val rad = Math.toRadians((a1 + i * 90.0))
            canvas.drawCircle(
                cx + (r * 0.92f) * cos(rad).toFloat(),
                cy + (r * 0.92f) * sin(rad).toFloat(),
                1.8f * dp, pDot
            )
        }

        // Middle diamond — CCW, faint
        drawDiamond(canvas, cx, cy, r * 0.60f, a2, pMid)

        // Inner diamond — fast CW, bright
        drawDiamond(canvas, cx, cy, r * 0.30f, a3, pInner)

        // Center dot
        canvas.drawCircle(cx, cy, 2f * dp, pCenter)

        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, r: Float, angleDeg: Float, paint: Paint) {
        path.reset()
        for (i in 0..3) {
            val rad = Math.toRadians((angleDeg + i * 90.0))
            val x = cx + r * cos(rad).toFloat()
            val y = cy + r * sin(rad).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
