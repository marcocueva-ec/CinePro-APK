package com.cinepro.camera.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class RgbHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rPath = Path()
    private val gPath = Path()
    private val bPath = Path()

    private val rPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFF1744")
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val gPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA00E676")
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val bPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA2979FF")
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private var phase = 0f

    fun updateFromExposure(isoNorm: Float, shutterNorm: Float) {
        phase += 0.15f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Grid lines inside histogram box
        canvas.drawLine(w * 0.25f, 0f, w * 0.25f, h, gridPaint)
        canvas.drawLine(w * 0.5f, 0f, w * 0.5f, h, gridPaint)
        canvas.drawLine(w * 0.75f, 0f, w * 0.75f, h, gridPaint)
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, gridPaint)

        buildChannelPath(rPath, w, h, 0.30f, 0.75f)
        buildChannelPath(gPath, w, h, 0.52f, 0.85f)
        buildChannelPath(bPath, w, h, 0.72f, 0.90f)

        canvas.drawPath(rPath, rPaint)
        canvas.drawPath(gPath, gPaint)
        canvas.drawPath(bPath, bPaint)
    }

    private fun buildChannelPath(path: Path, w: Float, h: Float, peakCenter: Float, peakHeight: Float) {
        path.reset()
        path.moveTo(0f, h)
        val steps = 32
        for (i in 0..steps) {
            val x = (i.toFloat() / steps) * w
            val normX = i.toFloat() / steps
            val dist = (normX - peakCenter) * 4.2f
            val bell = kotlin.math.exp((-dist * dist).toDouble()).toFloat()
            val ripple = (sin(normX * 18f + phase) * 0.08f).coerceAtLeast(0f)
            val y = h - ((bell * peakHeight + ripple).coerceIn(0.05f, 0.95f) * h)
            path.lineTo(x, y)
        }
        path.lineTo(w, h)
        path.close()
    }
}
