package com.google.ar.core.examples.kotlin.helloar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class DistanceTextView(context: Context, attrs: AttributeSet?) : AppCompatTextView(context, attrs) {

//    private val paint = Paint().apply {
//        color = Color.WHITE
//        textSize = 60f
//        typeface = Typeface.MONOSPACE
//        textAlign = Paint.Align.CENTER
//    }

//    var distanceText: String = ""
//        set(value) {
//            field = value
//            invalidate() // Redraw the view when the text changes
//        }

//    override fun onDraw(canvas: Canvas) {
//        super.onDraw(canvas)
//        val lines = distanceText.split("\n")
//        val xPos = width / 2f
//        val yStart = height / 2f - (lines.size - 1) * paint.textSize / 2f // Adjust for multiple lines
//        for ((index, line) in lines.withIndex()) {
//            val yPos = yStart + index * paint.textSize
//            canvas.drawText(line, xPos, yPos, paint)
//        }
//    }
}