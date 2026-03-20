package com.jules.loader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.jules.loader.R

class PromptDividerItemDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 0.5f, context.resources.displayMetrics)

        val typedValue = TypedValue()
        val theme = context.theme
        if (theme.resolveAttribute(R.attr.promptDividerColor, typedValue, true)) {
            color = if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT
            ) {
                typedValue.data
            } else {
                ContextCompat.getColor(context, typedValue.resourceId)
            }
        } else {
            // Fallback
            color = 0xFF424242.toInt()
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val childCount = parent.childCount
        for (i in 0 until childCount - 1) { // Don't draw after the last item
            val child = parent.getChildAt(i)
            val params = child.layoutParams as RecyclerView.LayoutParams

            val top = child.bottom + params.bottomMargin.toFloat()
            val left = parent.width * 0.075f // Start at 7.5% (leaving 85% width centered)
            val right = parent.width * 0.925f // End at 92.5%

            c.drawLine(left, top, right, top, paint)
        }
    }
}
