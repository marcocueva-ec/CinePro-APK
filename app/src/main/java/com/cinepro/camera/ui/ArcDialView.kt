package com.cinepro.camera.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class ArcDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var progress: Float = 0.5f // 0.0 to 1.0
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var accentMode: Int = 0 // 0 = Cyan, 1 = Cyan+Red (ISO), 2 = Warm/Cool (WB)
    var onValueChanged: ((Float) -> Unit)? = null

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#252B31")
    }

    private val knobBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#3E4750")
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E0F7FA")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.92f
        val outerRadius = min(width / 2f, height.toFloat()) * 0.85f
        val innerKnobRadius = outerRadius * 0.58f

        // Draw ticks from 195 deg to 345 deg
        val totalTicks = 28
        val startAngle = 195f
        val sweepAngle = 150f

        for (i in 0..totalTicks) {
            val fraction = i.toFloat() / totalTicks
            val angleDeg = startAngle + fraction * sweepAngle
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val isActive = fraction <= progress
            tickPaint.color = when {
                accentMode == 1 && fraction > 0.75f -> if (isActive) Color.parseColor("#FF3B30") else Color.parseColor("#66FF3B30")
                accentMode == 2 -> {
                    if (fraction < 0.5f) Color.parseColor("#4DD0E1") else Color.parseColor("#FFB74D")
                }
                isActive -> Color.parseColor("#38E8FF")
                else -> Color.parseColor("#455A64")
            }

            val rStart = outerRadius * 0.74f
            val rEnd = outerRadius * 0.92f
            val x1 = cx + (rStart * cos(angleRad)).toFloat()
            val y1 = cy + (rStart * sin(angleRad)).toFloat()
            val x2 = cx + (rEnd * cos(angleRad)).toFloat()
            val y2 = cy + (rEnd * sin(angleRad)).toFloat()

            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // Draw semi-circular inner rotary knob
        canvas.drawCircle(cx, cy, innerKnobRadius, knobPaint)
        canvas.drawCircle(cx, cy, innerKnobRadius, knobBorderPaint)

        // Draw indicator needle on knob
        val currentAngleDeg = startAngle + progress * sweepAngle
        val currentRad = Math.toRadians(currentAngleDeg.toDouble())
        val nx1 = cx + (innerKnobRadius * 0.35f * cos(currentRad)).toFloat()
        val ny1 = cy + (innerKnobRadius * 0.35f * sin(currentRad)).toFloat()
        val nx2 = cx + (innerKnobRadius * 0.82f * cos(currentRad)).toFloat()
        val ny2 = cy + (innerKnobRadius * 0.82f * sin(currentRad)).toFloat()

        canvas.drawLine(nx1, ny1, nx2, ny2, indicatorPaint)
    }

    private var lastTouchX = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                val sensitivity = width.coerceAtLeast(100) * 1.1f
                progress = (progress + dx / sensitivity).coerceIn(0f, 1f)
                onValueChanged?.invoke(progress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
