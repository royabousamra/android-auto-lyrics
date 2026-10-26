package com.roy.carlyrics.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/** Phone-side preview that uses the exact same renderer as the car screen. */
class KaraokeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val renderer = KaraokeRenderer()
    private val area = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        area.set(0, 0, width, height)
        renderer.draw(canvas, area, FrameSource.current())
        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
