// In a new file: BoundingBoxView.kt
package com.google.ar.core.examples.kotlin.helloar.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.CopyOnWriteArrayList

class BoundingBoxView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val boundingBoxes = CopyOnWriteArrayList<BoundingBox>()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 50f
        style = Paint.Style.FILL
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // The object detector provides normalized coordinates (0-1). We need to scale them
        // to the view's size.
        for (box in boundingBoxes) {
            val left = box.x1 * width
            val top = box.y1 * height
            val right = box.x2 * width
            val bottom = box.y2 * height

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Draw label with background
            val label = "${box.clsName} ${String.format("%.2f", box.cnf)}"
            val textBounds = Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            val textWidth = textPaint.measureText(label)

            // Draw background for text
            canvas.drawRect(
                left,
                top,
                left + textWidth + 10f, // padding
                top + textBounds.height() + 10f, // padding
                textBackgroundPaint
            )

            // Draw text
            canvas.drawText(label, left, top + textBounds.height(), textPaint)

            // Draw depth next to label
            if (box.depth != null) {
                val depthText = "%.2fm".format(box.depth!! / 1000.0f)
                val depthTextWidth = textPaint.measureText(depthText)
                canvas.drawText(depthText, left + textWidth + 20f, top + textBounds.height(), textPaint)
            }
        }
    }

    // This method will be called from HelloArRenderer to update the boxes
    fun setResults(boxes: List<BoundingBox>) {
        boundingBoxes.clear()
        boundingBoxes.addAll(boxes)
        // Invalidate the view to force a redraw
        invalidate()
    }
}
