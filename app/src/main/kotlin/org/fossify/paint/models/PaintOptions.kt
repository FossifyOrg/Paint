package org.fossify.paint.models

import android.graphics.Color
import android.graphics.Paint

data class PaintOptions(
    var color: Int = Color.BLACK,
    var strokeWidth: Float = 5f,
    var isEraser: Boolean = false,
    var isFill: Boolean = false
) {
    fun applyTo(paint: Paint, backgroundColor: Int) {
        paint.color = if (isEraser) backgroundColor else color
        paint.style = if (isFill) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
    }
}
