package com.roy.carlyrics.art

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

/**
 * Colours derived from the cover art. Backgrounds are kept dark and the accent light so white
 * lyrics stay readable whatever the cover looks like.
 */
data class ArtTheme(
    val backgroundTop: Int,
    val backgroundBottom: Int,
    /** Light, saturated colour for the progress bar and other highlights. */
    val accent: Int,
    /** White with a hint of the accent, used for lyrics that aren't being sung. */
    val textTint: Int,
) {
    companion object {
        val DEFAULT = ArtTheme(
            backgroundTop = Color.rgb(58, 28, 92),
            backgroundBottom = Color.rgb(16, 12, 30),
            accent = Color.rgb(255, 95, 135),
            textTint = Color.rgb(255, 228, 236),
        )

        /** Blocking (runs Palette); call off the main thread. */
        fun from(cover: Bitmap): ArtTheme {
            val palette = Palette.from(cover).maximumColorCount(24).generate()
            val dominant = palette.dominantSwatch?.rgb ?: return DEFAULT

            val topSource = palette.darkVibrantSwatch?.rgb ?: palette.vibrantSwatch?.rgb ?: dominant
            val bottomSource = palette.darkMutedSwatch?.rgb ?: palette.mutedSwatch?.rgb ?: dominant
            val accentSource = palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.lightMutedSwatch?.rgb
                ?: dominant

            val accent = accent(accentSource)
            return ArtTheme(
                backgroundTop = tone(topSource, value = 0.40f, maxSat = 0.80f),
                backgroundBottom = tone(bottomSource, value = 0.14f, maxSat = 0.60f),
                accent = accent,
                textTint = mix(Color.WHITE, accent, 0.28f),
            )
        }

        /** Same hue, brightness pinned to [value] so backgrounds never get light. */
        private fun tone(color: Int, value: Float, maxSat: Float): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = hsv[1].coerceAtMost(maxSat)
            hsv[2] = value
            return Color.HSVToColor(hsv)
        }

        /** Bright enough to stand out on the dark background; near-white for greyscale covers. */
        private fun accent(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = if (hsv[1] < 0.12f) 0.06f else hsv[1].coerceIn(0.40f, 0.85f)
            hsv[2] = hsv[2].coerceAtLeast(0.90f)
            return Color.HSVToColor(hsv)
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
            )
        }
    }
}
