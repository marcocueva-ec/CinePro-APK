package com.cinepro.camera.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class CurvedZoomView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var zoomProgress: Float = 0f // 0.0 (1.0x) to 1.0 (Max Zoom)
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var onZoomChanged: ((Float) -> Unit)? = null

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 28f
        color = Color.parseColor("#881C2228")
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#38E8FF")
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 1.85f
        val cy = height / 2f
        val radius = width * 1.55f

        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, 148f, 64f, false, arcPaint)

        val totalTicks = 40
        for (i in 0..totalTicks) {
            val frac = i.toFloat() / totalTicks
            val angleDeg = 150f + frac * 60f
            val rad = Math.toRadians(angleDeg.toDouble())

            val isNearThumb = abs(frac - (1f - zoomProgress)) < 0.04f
            tickPaint.strokeWidth = if (isNearThumb || i % 5 == 0) 5f else 2.5f
            tickPaint.color = if (isNearThumb) Color.WHITE else Color.parseColor("#38E8FF")

            val rIn = radius - (if (isNearThumb) 18f else 10f)
            val rOut = radius + (if (isNearThumb) 18f else 10f)

            val x1 = cx + (rIn * cos(rad)).toFloat()
            val y1 = cy + (rIn * sin(rad)).toFloat()
            val x2 = cx + (rOut * cos(rad)).toFloat()
            val y2 = cy + (rOut * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
    }

    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastY - event.y
                lastY = event.y
                zoomProgress = (zoomProgress + dy / (height * 0.7f)).coerceIn(0f, 1f)
                onZoomChanged?.invoke(zoomProgress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
