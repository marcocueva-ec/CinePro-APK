package com.cinepro.camera.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CineOverlaysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 0 = OFF, 1 = Regla de Tercios, 2 = Tercios + Cruz Central
    var gridMode: Int = 1
    var showLevel: Boolean = true
    var lutEnabled: Boolean = false

    // Focus Peaking: 0 = OFF, 1..100 = Sensibilidad (mas alto = mas peaking visible)
    var peakingSensitivity: Int = 0
    var peakingBitmap: Bitmap? = null

    // Rectangulo exacto del area activa de imagen (Open Gate 4:3 o 16:9)
    val activeImageRect = RectF()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FFFFFF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5538E8FF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9938E8FF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val lutOverlayPaint = Paint().apply {
        color = Color.parseColor("#1800E5FF")
    }

    private val peakingPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = if (activeImageRect.width() > 10f && activeImageRect.height() > 10f) {
            activeImageRect
        } else {
            RectF(0f, 0f, width.toFloat(), height.toFloat())
        }

        // 1. Previsualizacion de LUT
        if (lutEnabled) {
            canvas.drawRect(rect, lutOverlayPaint)
        }

        // 2. Capa de Focus Peaking en tiempo real
        if (peakingSensitivity > 0) {
            peakingBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, rect, peakingPaint)
                }
            }
        }

        // 3. Borde sutil del area del sensor (util en Open Gate)
        canvas.drawRect(rect, frameBorderPaint)

        // 4. Grid de Regla de Tercios (3x3)
        if (gridMode >= 1) {
            val thirdW = rect.width() / 3f
            val thirdH = rect.height() / 3f

            // Lineas verticales de tercios
            canvas.drawLine(rect.left + thirdW, rect.top, rect.left + thirdW, rect.bottom, gridPaint)
            canvas.drawLine(rect.left + thirdW * 2f, rect.top, rect.left + thirdW * 2f, rect.bottom, gridPaint)

            // Lineas horizontales de tercios
            canvas.drawLine(rect.left, rect.top + thirdH, rect.right, rect.top + thirdH, gridPaint)
            canvas.drawLine(rect.left, rect.top + thirdH * 2f, rect.right, rect.top + thirdH * 2f, gridPaint)

            // Cruz central de encuadre si gridMode == 2
            if (gridMode == 2) {
                val cx = rect.centerX()
                val cy = rect.centerY()
                canvas.drawLine(cx - 24f, cy, cx + 24f, cy, frameBorderPaint)
                canvas.drawLine(cx, cy - 24f, cx, cy + 24f, frameBorderPaint)
            }
        }

        // 5. Nivel de horizonte central
        if (showLevel) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            canvas.drawLine(cx - 85f, cy, cx - 22f, cy, levelPaint)
            canvas.drawLine(cx + 22f, cy, cx + 85f, cy, levelPaint)
            canvas.drawCircle(cx, cy, 7f, levelPaint)
        }
    }
}
