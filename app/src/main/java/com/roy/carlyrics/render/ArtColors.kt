package com.roy.carlyrics.render

import android.graphics.Bitmap
import android.graphics.Color

/** Picks two dark, album-tinted background colours from cover art (no AndroidX Palette needed). */
object ArtColors {

    val DEFAULT = intArrayOf(Color.rgb(58, 28, 92), Color.rgb(18, 14, 34))

    fun from(art: Bitmap?): IntArray {
        if (art == null || art.isRecycled) return DEFAULT
        return try {
            val soft = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
            val small = Bitmap.createScaledBitmap(soft, 24, 24, true)
            val pixels = IntArray(24 * 24)
            small.getPixels(pixels, 0, 24, 0, 0, 24, 24)

            val hsv = FloatArray(3)
            var bestScore = -1f
            var vibrant = DEFAULT[0]
            var r = 0L; var g = 0L; var b = 0L
            for (p in pixels) {
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                Color.colorToHSV(p, hsv)
                val score = hsv[1] * hsv[2] * (if (hsv[2] > 0.2f) 1f else 0.1f)
                if (score > bestScore) { bestScore = score; vibrant = p }
            }
            val n = pixels.size
            val avg = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            intArrayOf(darken(vibrant, 0.48f, 0.85f), darken(avg, 0.20f, 0.6f))
        } catch (t: Throwable) {
            DEFAULT
        }
    }

    /** Clamp brightness to [value] and saturation to [maxSat] so white text always stays readable. */
    private fun darken(color: Int, value: Float, maxSat: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceAtMost(maxSat)
        hsv[2] = minOf(hsv[2], value).coerceAtLeast(value * 0.6f)
        return Color.HSVToColor(hsv)
    }

    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
        )
    }
}
