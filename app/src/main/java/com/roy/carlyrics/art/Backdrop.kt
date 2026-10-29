package com.roy.carlyrics.art

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Builds the soft-focus, darkened copy of the cover that fills the screen behind the lyrics.
 * It is small (128 px) and lightly blurred: drawn stretched with bilinear filtering, the cover
 * stays recognisable but no detail competes with the text.
 */
object Backdrop {

    private const val SIZE = 128

    fun from(cover: Bitmap): Bitmap {
        // Centre-crop to a square first so non-square art (video thumbnails) isn't squashed.
        val side = minOf(cover.width, cover.height)
        val square = Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)

        repeat(2) { boxBlur(px, SIZE, SIZE, radius = 2) }

        // Cap brightness and lift saturation a little so white lyrics always have contrast.
        val hsv = FloatArray(3)
        for (i in px.indices) {
            Color.colorToHSV(px[i], hsv)
            hsv[1] = (hsv[1] * 1.1f).coerceAtMost(1f)
            hsv[2] = hsv[2].coerceAtMost(0.62f)
            px[i] = Color.HSVToColor(hsv)
        }
        return Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /** Separable box blur with clamped edges, in place. */
    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, radius, horizontal = true)
        pass(tmp, px, w, h, radius, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val n = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + c] else src[c * w + line]
            }
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = at(i)
                sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p)
            }
            for (i in 0 until len) {
                val out = Color.rgb(sr / n, sg / n, sb / n)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val add = at(i + r + 1)
                val sub = at(i - r)
                sr += Color.red(add) - Color.red(sub)
                sg += Color.green(add) - Color.green(sub)
                sb += Color.blue(add) - Color.blue(sub)
            }
        }
    }
}
