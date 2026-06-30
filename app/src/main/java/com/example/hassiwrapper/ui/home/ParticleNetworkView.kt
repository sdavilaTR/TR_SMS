package com.example.hassiwrapper.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ParticleNetworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float)

    private val particles = mutableListOf<Particle>()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowA = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x041565C0.toInt() }
    private val glowB = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x081565C0.toInt() }
    private val glowC = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x101565C0.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x501565C0.toInt() }

    private var density = 1f
    private var connectDist = 0f
    private var dotRadius = 0f
    private var speed = 0f
    private var initialized = false

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        density = resources.displayMetrics.density
        connectDist = 120f * density
        dotRadius = 2f * density
        speed = 0.45f * density
        linePaint.strokeWidth = 0.9f * density

        if (!initialized) {
            val area = w.toLong() * h
            val count = (area / (density * density * 2200)).toInt().coerceIn(14, 26)
            val rng = Random.Default
            repeat(count) {
                val angle = rng.nextFloat() * 2f * Math.PI.toFloat()
                val spd = speed * (0.35f + rng.nextFloat() * 0.65f)
                particles += Particle(
                    x = rng.nextFloat() * w,
                    y = rng.nextFloat() * h,
                    vx = spd * cos(angle.toDouble()).toFloat(),
                    vy = spd * sin(angle.toDouble()).toFloat()
                )
            }
            initialized = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        try {
            doDraw(canvas)
        } catch (ignored: Exception) {
            // never crash the host fragment over a cosmetic effect
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun doDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f || particles.isEmpty()) return

        for (p in particles) {
            p.x += p.vx; p.y += p.vy
            if (p.x < 0f) { p.x = 0f; p.vx = -p.vx }
            if (p.x > w) { p.x = w; p.vx = -p.vx }
            if (p.y < 0f) { p.y = 0f; p.vy = -p.vy }
            if (p.y > h) { p.y = h; p.vy = -p.vy }
        }

        for (i in particles.indices) {
            for (j in i + 1 until particles.size) {
                val a = particles[i]; val b = particles[j]
                val dx = a.x - b.x; val dy = a.y - b.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < connectDist) {
                    val alpha = ((1f - dist / connectDist) * 45).toInt()
                    linePaint.color = (alpha shl 24) or 0x1565C0
                    canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
                }
            }
        }

        for (p in particles) {
            canvas.drawCircle(p.x, p.y, dotRadius * 7f, glowA)
            canvas.drawCircle(p.x, p.y, dotRadius * 4f, glowB)
            canvas.drawCircle(p.x, p.y, dotRadius * 2.2f, glowC)
            canvas.drawCircle(p.x, p.y, dotRadius, dotPaint)
        }

    }
}
