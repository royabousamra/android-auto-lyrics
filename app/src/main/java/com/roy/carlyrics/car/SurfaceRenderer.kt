package com.roy.carlyrics.car

import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.roy.carlyrics.render.FrameSource
import com.roy.carlyrics.render.KaraokeRenderer

/**
 * Owns the Android Auto drawing surface and repaints it every display frame while the screen
 * is visible. Drawing happens on the main thread via Choreographer, which is cheap enough for
 * a few lines of text.
 */
class SurfaceRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
) : SurfaceCallback, DefaultLifecycleObserver, Choreographer.FrameCallback {

    private val renderer = KaraokeRenderer()
    private var surface: Surface? = null
    private val fullArea = Rect()
    private var visibleArea: Rect? = null
    private var running = false

    init {
        lifecycle.addObserver(this)
    }

    // ---- Lifecycle ----

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        surface = null
    }

    // ---- SurfaceCallback ----

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container.surface
        fullArea.set(0, 0, container.width, container.height)
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
    }

    /** The part of the surface not covered by the action strip or other host UI. */
    override fun onVisibleAreaChanged(area: Rect) {
        visibleArea = Rect(area)
    }

    override fun onStableAreaChanged(area: Rect) = Unit

    // ---- Frame loop ----

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        draw()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun draw() {
        val s = surface ?: return
        if (!s.isValid) return
        val canvas = try {
            s.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.w("SurfaceRenderer", "lockHardwareCanvas failed", e)
            return
        }
        try {
            val area = visibleArea?.takeIf { !it.isEmpty } ?: fullArea
            renderer.draw(canvas, area, FrameSource.current())
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }
}
