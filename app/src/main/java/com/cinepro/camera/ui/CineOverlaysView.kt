package com.cinepro.camera.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class CineOverlaysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var showGrid: Boolean = true
    var showZebra: Boolean = false
    var showPeaking: Boolean = false
    var showLevel: Boolean = true
    var lutEnabled: Boolean = false

    // Simulated or real-time RGB histogram bins (64 bins)
    val rBins = FloatArray(64) { 0.2f }
    val gBins = FloatArray(64) { 0.3f }
    val bBins = FloatArray(64) { 0.25f }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8838E8FF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val lutOverlayPaint = Paint().apply {
        color = Color.parseColor("#1800E5FF") // Subtle Teal/Orange LUT preview tint
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (lutEnabled) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), lutOverlayPaint)
        }

        if (showGrid) {
            val w3 = width / 3f
            val h3 = height / 3f
            canvas.drawLine(w3, 0f, w3, height.toFloat(), gridPaint)
            canvas.drawLine(w3 * 2, 0f, w3 * 2, height.toFloat(), gridPaint)
            canvas.drawLine(0f, h3, width.toFloat(), h3, gridPaint)
            canvas.drawLine(0f, h3 * 2, width.toFloat(), h3 * 2, gridPaint)
        }

        if (showLevel) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawLine(cx - 90f, cy, cx - 25f, cy, levelPaint)
            canvas.drawLine(cx + 25f, cy, cx + 90f, cy, levelPaint)
            canvas.drawCircle(cx, cy, 8f, levelPaint)
        }
    }
}
