package com.roy.carlyrics.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import com.roy.carlyrics.art.ArtTheme
import com.roy.carlyrics.art.Backdrop
import com.roy.carlyrics.art.CoverArt
import com.roy.carlyrics.lyrics.LrcParser
import com.roy.carlyrics.lyrics.LyricsState
import com.roy.carlyrics.media.NowPlaying
import com.roy.carlyrics.media.PlaybackSnapshot
import com.roy.carlyrics.media.Track
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the karaoke screen off-device into PNGs (app/build/screenshots) so the look can be
 * checked without a car. CI uploads them with the APK.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ScreenshotTest {

    // Original placeholder lyrics, written for this test.
    private val lrc = """
        [00:10.00]Headlights painting lines across the rain
        [00:14.20]Every mile a verse I can't explain
        [00:18.40]Turn it up and let the city sing
        [00:22.60]We were never meant for standing still
        [00:26.80]Hold the note until the morning comes
        [00:31.00]Hold the note until the morning comes
    """.trimIndent()

    private val track = Track("Night Drive", "The Example Band", "Test Album", 200_000L, "test.player")

    @Test
    fun carScreen() = render("car", 1920, 720, coverA(), positionMs = 19_600L)

    @Test
    fun carScreenOtherCover() = render("car-cover-b", 1920, 720, coverB(), positionMs = 15_800L)

    @Test
    fun phoneScreen() = render("phone", 1080, 2100, coverA(), positionMs = 19_600L)

    @Test
    fun carNoCover() = render("car-no-cover", 1920, 720, null, positionMs = 23_500L)

    private fun render(name: String, w: Int, h: Int, cover: Bitmap?, positionMs: Long) {
        val lyrics = LrcParser.parse(lrc, track.durationMs)
        val np = NowPlaying(track, PlaybackSnapshot(positionMs, 0L, 1f, isPlaying = true), cover)
        val coverArt = if (cover == null) {
            CoverArt(track.key, null, null, ArtTheme.DEFAULT, CoverArt.Source.NONE)
        } else {
            CoverArt(track.key, cover, Backdrop.from(cover), ArtTheme.from(cover), CoverArt.Source.PLAYER_IMAGE)
        }
        val frame = KaraokeRenderer.Frame(np, LyricsState.Synced(track, lyrics), positionMs, positionMs, coverArt)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val renderer = KaraokeRenderer()
        // Run enough frames for fades and theme transitions to settle.
        repeat(70) {
            renderer.draw(canvas, Rect(0, 0, w, h), frame)
            Thread.sleep(30)
        }
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Synthetic sunset cover. */
    private fun coverA(): Bitmap {
        val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 600f, Color.rgb(255, 120, 60), Color.rgb(90, 20, 120), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 600f, 600f, p)
        p.shader = RadialGradient(300f, 340f, 150f, Color.rgb(255, 230, 120), Color.rgb(255, 150, 60), Shader.TileMode.CLAMP)
        c.drawCircle(300f, 340f, 140f, p)
        p.shader = null
        p.color = Color.rgb(30, 10, 50)
        c.drawRect(0f, 420f, 600f, 600f, p)
        return b
    }

    /** Synthetic teal/blue cover. */
    private fun coverB(): Bitmap {
        val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 600f, 600f, Color.rgb(10, 40, 80), Color.rgb(0, 170, 170), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 600f, 600f, p)
        p.shader = null
        p.color = Color.rgb(240, 240, 255)
        c.drawCircle(420f, 180f, 90f, p)
        p.color = Color.rgb(20, 220, 160)
        c.drawRect(60f, 380f, 540f, 440f, p)
        return b
    }
}
