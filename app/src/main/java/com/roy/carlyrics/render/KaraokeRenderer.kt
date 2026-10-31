package com.roy.carlyrics.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import com.roy.carlyrics.art.ArtTheme
import com.roy.carlyrics.art.CoverArt
import com.roy.carlyrics.lyrics.LrcParser
import com.roy.carlyrics.lyrics.LyricLine
import com.roy.carlyrics.lyrics.LyricsState
import com.roy.carlyrics.lyrics.progress
import com.roy.carlyrics.media.NowPlaying
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws Apple Music style karaoke lyrics onto any [Canvas]. Used both by the Android Auto
 * surface and by the phone preview, so what you test on the phone is what the car shows.
 *
 * Look and feel:
 *  - lyrics use the whole screen; the song's cover sits behind them as a background: dimmed,
 *    full height on the right of wide (car) screens or full width at the top of tall (phone)
 *    screens, fading into a slowly drifting blurred wash of its own colours
 *  - a colour theme taken from the cover tints the dimmed lyrics and the thin progress bar
 *  - large heavy left-aligned text; the active line is bright and full size, others dimmed
 *  - lines light up as a whole; only lyrics with real word timing (enhanced LRC) fill
 *    word by word, left-to-right with a soft edge, lifting slightly as they are sung
 *  - lines scroll on springs, lines further down lag a little (the "cascade")
 *  - instrumental gaps show three breathing dots that fill up over the gap
 *
 * Not thread safe: call [draw] from one thread.
 */
class KaraokeRenderer {

    data class Frame(
        val nowPlaying: NowPlaying?,
        val lyrics: LyricsState,
        /** Playback position corrected by the user's sync offset; drives the lyrics. */
        val positionMs: Long,
        /** Raw playback position; drives the progress bar at the bottom edge. */
        val playbackMs: Long,
        val cover: CoverArt?,
    )

    // ---- Paints ----

    private val heavy: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 800, false)
        else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private val lyricPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = heavy
        color = Color.WHITE
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bgPaint = Paint()
    private val scrimPaint = Paint()
    private val sideScrimPaint = Paint()
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // Cover drawn at ~60% brightness so lyrics on top stay readable.
    private val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = LightingColorFilter(0x9A9A9A, 0)
    }
    private val coverMaskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var coverMaskKey = ""
    private val coverRect = RectF()
    private val srcRect = Rect()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = RectF()

    // ---- Theme + cover animation ----

    private var targetTheme = ArtTheme.DEFAULT
    /** backgroundTop, backgroundBottom, accent, textTint as currently shown (animated). */
    private val shown = intArrayOf(
        ArtTheme.DEFAULT.backgroundTop, ArtTheme.DEFAULT.backgroundBottom,
        ArtTheme.DEFAULT.accent, ArtTheme.DEFAULT.textTint,
    )
    private val accent get() = shown[2]
    private val tint get() = shown[3]
    private var bgShaderKey = ""

    private var currentCover: CoverArt? = null
    private var image: Bitmap? = null
    private var prevImage: Bitmap? = null
    private var imageFade = 1f
    private var backdrop: Bitmap? = null
    private var prevBackdrop: Bitmap? = null
    private var backdropFade = 1f

    // ---- Lyrics layout ----

    private class WordBox(val startMs: Long, val endMs: Long, val text: String, val row: Int, val x: Float, val width: Float)

    private class LineBox(
        val line: LyricLine?, // null for unsynced plain lyrics
        val words: List<WordBox>,
        val top: Float,
        val height: Float,
    ) {
        val isInterlude get() = line is LyricLine.Interlude
    }

    private var layoutKey: Any? = null
    private var boxes: List<LineBox> = emptyList()
    private var totalHeight = 0f
    private var textSize = 0f
    private var rowHeight = 0f

    // Per-line animation state.
    private var offset = FloatArray(0)
    private var velocity = FloatArray(0)
    private var activeness = FloatArray(0)
    private var needsSnap = true

    private var lastFrameNanos = 0L
    private var lastActive = Int.MIN_VALUE

    fun draw(canvas: Canvas, area: Rect, frame: Frame) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        val timeSec = now / 1e9

        updateCover(frame, dt)
        drawBackground(canvas, timeSec)
        if (area.width() <= 0 || area.height() <= 0) return

        val pad = min(area.width(), area.height()) * 0.06f
        val np = frame.nowPlaying
        if (np != null) {
            // The only chrome: a hairline progress bar along the bottom edge.
            val barHeight = max(3f, min(area.width(), area.height()) * 0.006f)
            drawProgress(canvas, area.left + pad, area.bottom - pad * 0.3f - barHeight, area.width() - pad * 2f,
                barHeight, frame.playbackMs, np.track.durationMs)
        }
        val region = RectF(area.left + pad, area.top + pad * 0.5f, area.right - pad, area.bottom - pad * 0.6f)

        val baseSize = min(region.height() / 6.2f, region.width() / 14f).coerceAtLeast(18f)
        if (region.height() < baseSize || region.width() < baseSize * 3) return

        when (val s = frame.lyrics) {
            is LyricsState.Synced -> drawLyrics(canvas, region, baseSize, s.lyrics.lines, null, frame, dt)
            is LyricsState.Plain -> drawLyrics(canvas, region, baseSize * 0.8f, null, s.lines, frame, dt)
            LyricsState.NoMusic -> drawMessage(canvas, region, baseSize, "Play something", "Lyrics follow whatever music app is playing")
            is LyricsState.Loading -> drawMessage(canvas, region, baseSize, "Finding lyrics…", "${s.track.artist} – ${s.track.title}")
            is LyricsState.Instrumental -> drawMessage(canvas, region, baseSize, "♪  Instrumental", "${s.track.artist} – ${s.track.title}")
            is LyricsState.NotFound -> drawMessage(canvas, region, baseSize, "No lyrics found", "${s.track.artist} – ${s.track.title}")
            is LyricsState.Failed -> drawMessage(canvas, region, baseSize, "Couldn't load lyrics", s.message)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Cover + theme state

    private fun updateCover(frame: Frame, dt: Float) {
        val np = frame.nowPlaying
        if (np == null) {
            if (currentCover != null) {
                currentCover = null
                swapImages(null, null)
            }
            targetTheme = ArtTheme.DEFAULT
        } else {
            // Keep the previous cover until the new song's cover is ready, so nothing flashes.
            val cover = frame.cover?.takeIf { it.trackKey == np.track.key }
            if (cover != null && cover !== currentCover) {
                currentCover = cover
                swapImages(cover.image, cover.backdrop)
                targetTheme = cover.theme
            }
        }

        imageFade = min(1f, imageFade + dt * 1.5f)
        backdropFade = min(1f, backdropFade + dt * 1.2f)
        if (imageFade >= 1f) prevImage = null
        if (backdropFade >= 1f) prevBackdrop = null

        val k = 1f - exp(-dt * 2.5f)
        val target = intArrayOf(targetTheme.backgroundTop, targetTheme.backgroundBottom, targetTheme.accent, targetTheme.textTint)
        for (i in shown.indices) shown[i] = ArtTheme.mix(shown[i], target[i], k)
    }

    private fun swapImages(newImage: Bitmap?, newBackdrop: Bitmap?) {
        if (newImage !== image) {
            prevImage = image
            image = newImage
            imageFade = 0f
        }
        if (newBackdrop !== backdrop) {
            prevBackdrop = backdrop
            backdrop = newBackdrop
            backdropFade = 0f
        }
    }

    // ------------------------------------------------------------------------------------------
    // Background: theme gradient, blurred colour wash, the cover itself, then scrims for contrast

    private fun drawBackground(canvas: Canvas, timeSec: Double) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val key = "${shown[0]}|${shown[1]}|$w|$h"
        if (key != bgShaderKey) {
            bgShaderKey = key
            bgPaint.shader = LinearGradient(0f, 0f, w * 0.6f, h, shown[0], shown[1], Shader.TileMode.CLAMP)
            // Lyrics are left-aligned, so the left side gets the heavier scrim and the cover
            // shows through most on the right.
            sideScrimPaint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(withAlpha(shown[1], 0.72f), withAlpha(shown[1], 0.45f), withAlpha(shown[1], 0.25f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            scrimPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(withAlpha(Color.BLACK, 0.10f), withAlpha(Color.BLACK, 0f), withAlpha(Color.BLACK, 0.35f)),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        drawBackdrop(canvas, prevBackdrop, 1f - backdropFade, w, h, timeSec)
        drawBackdrop(canvas, backdrop, backdropFade, w, h, timeSec)
        drawCover(canvas, prevImage, 1f - imageFade, w, h, timeSec)
        drawCover(canvas, image, imageFade, w, h, timeSec)
        canvas.drawRect(0f, 0f, w, h, sideScrimPaint)
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
    }

    /** The blurred wash, filling the whole surface, with a slow "Ken Burns" drift. */
    private fun drawBackdrop(canvas: Canvas, b: Bitmap?, alpha: Float, w: Float, h: Float, timeSec: Double) {
        if (b == null || alpha <= 0.01f) return
        val zoom = 1.08f + 0.06f * sin(timeSec * 2 * PI / 40.0).toFloat()
        val driftX = w * 0.02f * sin(timeSec * 2 * PI / 55.0).toFloat()
        val driftY = h * 0.02f * sin(timeSec * 2 * PI / 47.0).toFloat()
        val side = max(w, h) * zoom
        val cx = w / 2f + driftX
        val cy = h / 2f + driftY
        tmpRect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        backdropPaint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(b, null, tmpRect, backdropPaint)
    }

    private fun drawProgress(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val p = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val r = height / 2f
        fillPaint.color = withAlpha(Color.WHITE, 0.22f)
        tmpRect.set(left, top, left + width, top + height)
        canvas.drawRoundRect(tmpRect, r, r, fillPaint)
        fillPaint.color = accent
        tmpRect.set(left, top, left + max(height, width * p), top + height)
        canvas.drawRoundRect(tmpRect, r, r, fillPaint)
    }

    private fun formatTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * The cover as a background image: a square filling the height on the right of wide screens,
     * or the width at the top of tall ones, faded out towards the lyrics with a gradient mask.
     */
    private fun drawCover(canvas: Canvas, bmp: Bitmap?, alpha: Float, w: Float, h: Float, timeSec: Double) {
        if (bmp == null || bmp.isRecycled || alpha <= 0.01f) return
        val wide = w >= h
        val size = if (wide) h else w
        if (wide) coverRect.set(w - size, 0f, w, size) else coverRect.set(0f, 0f, size, size)

        val key = "$wide|$w|$h"
        if (key != coverMaskKey) {
            coverMaskKey = key
            coverMaskPaint.shader = if (wide) {
                LinearGradient(coverRect.left, 0f, coverRect.left + size * 0.6f, 0f,
                    intArrayOf(0x00000000, 0x99000000.toInt(), 0xFF000000.toInt()), floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, coverRect.bottom, 0f, coverRect.bottom - size * 0.6f,
                    intArrayOf(0x00000000, 0x99000000.toInt(), 0xFF000000.toInt()), floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP)
            }
        }

        // Centre-crop non-square covers (e.g. video thumbnails).
        val side = min(bmp.width, bmp.height)
        val sx = (bmp.width - side) / 2
        val sy = (bmp.height - side) / 2
        srcRect.set(sx, sy, sx + side, sy + side)

        canvas.saveLayer(coverRect, null)
        // Very slow breathing zoom, anchored to the screen edge the cover sits against.
        val zoom = 1.02f + 0.02f * sin(timeSec * 2 * PI / 30.0).toFloat()
        canvas.save()
        if (wide) canvas.scale(zoom, zoom, coverRect.right, coverRect.centerY())
        else canvas.scale(zoom, zoom, coverRect.centerX(), coverRect.top)
        coverPaint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(bmp, srcRect, coverRect, coverPaint)
        canvas.restore()
        canvas.drawRect(coverRect, coverMaskPaint)
        canvas.restore()
    }

    // ------------------------------------------------------------------------------------------
    // Status messages

    private fun drawMessage(canvas: Canvas, region: RectF, baseSize: Float, title: String, subtitle: String?) {
        lyricPaint.shader = null
        lyricPaint.textSize = baseSize * 0.8f
        lyricPaint.color = withAlpha(Color.WHITE, 0.92f)
        val cy = region.centerY()
        canvas.drawText(title, region.left, cy, lyricPaint)
        if (subtitle != null) {
            subtitlePaint.textSize = baseSize * 0.4f
            subtitlePaint.color = withAlpha(tint, 0.65f)
            val s = TextUtils.ellipsize(subtitle, subtitlePaint, region.width(), TextUtils.TruncateAt.END)
            canvas.drawText(s, 0, s.length, region.left, cy + baseSize * 0.75f, subtitlePaint)
        }
        layoutKey = null // force a fresh layout + snap when lyrics arrive
    }


    // ------------------------------------------------------------------------------------------
    // Lyrics

    private fun ensureLayout(lines: List<LyricLine>?, plain: List<String>?, width: Float, size: Float) {
        val key = Triple(lines ?: plain, width, size)
        if (key == layoutKey) return
        layoutKey = key
        textSize = size
        rowHeight = size * 1.22f
        lyricPaint.textSize = size
        val lineGap = size * 0.7f

        val out = ArrayList<LineBox>()
        var y = 0f
        if (lines != null) {
            for (line in lines) {
                when (line) {
                    is LyricLine.Interlude -> {
                        val h = size * 0.9f
                        out += LineBox(line, emptyList(), y, h)
                        y += h + lineGap
                    }
                    is LyricLine.Sung -> {
                        val (boxes, rows) = wrap(line.words.map { Triple(it.text, it.startMs, it.endMs) }, width)
                        val h = rows * rowHeight
                        out += LineBox(line, boxes, y, h)
                        y += h + lineGap
                    }
                }
            }
        } else if (plain != null) {
            for (text in plain) {
                if (text.isBlank()) { y += lineGap; continue }
                val tokens = LrcParser.tokenize(text).map { Triple(it, 0L, 0L) }
                val (boxes, rows) = wrap(tokens, width)
                val h = rows * rowHeight
                out += LineBox(null, boxes, y, h)
                y += h + lineGap * 0.6f
            }
        }
        boxes = out
        totalHeight = y
        offset = FloatArray(out.size)
        velocity = FloatArray(out.size)
        activeness = FloatArray(out.size)
        needsSnap = true
        lastActive = Int.MIN_VALUE
    }

    /** Greedy word wrap. Tokens keep their trailing spaces; wrapping ignores trailing space width. */
    private fun wrap(tokens: List<Triple<String, Long, Long>>, width: Float): Pair<List<WordBox>, Int> {
        val out = ArrayList<WordBox>(tokens.size)
        var x = 0f
        var row = 0
        for ((text, start, end) in tokens) {
            val w = lyricPaint.measureText(text)
            val visible = lyricPaint.measureText(text.trimEnd())
            if (x > 0f && x + visible > width) {
                row++
                x = 0f
            }
            out += WordBox(start, end, text, row, x, w)
            x += w
        }
        return out to (row + 1)
    }

    private fun drawLyrics(
        canvas: Canvas,
        region: RectF,
        size: Float,
        lines: List<LyricLine>?,
        plain: List<String>?,
        frame: Frame,
        dt: Float,
    ) {
        ensureLayout(lines, plain, region.width(), size)
        if (boxes.isEmpty()) return
        val pos = frame.positionMs

        // Which line is active, and where should the list scroll to?
        val active: Int
        val target: Float
        if (lines != null) {
            active = activeIndex(lines, pos)
            val anchor = region.height() * 0.22f
            target = boxes[max(active, 0)].top - anchor
        } else {
            active = -1
            val dur = frame.nowPlaying?.track?.durationMs ?: 0L
            val p = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            target = p * max(0f, totalHeight - region.height() * 0.6f) - region.height() * 0.1f
        }

        // Big jumps (seek, first frame) snap instead of animating through every line.
        if (needsSnap || (lastActive != Int.MIN_VALUE && abs(active - lastActive) > 3)) {
            offset.fill(target)
            velocity.fill(0f)
            for (i in activeness.indices) activeness[i] = if (i == active) 1f else 0f
            needsSnap = false
        }
        lastActive = active

        stepSprings(target, active, dt)

        canvas.save()
        canvas.clipRect(region.left - size, region.top - size * 0.5f, region.right + size, region.bottom)
        val timeSec = System.nanoTime() / 1e9

        for (i in boxes.indices) {
            val box = boxes[i]
            val y = region.top + box.top - offset[i]
            if (y + box.height < region.top - size || y > region.bottom) continue

            // Fade lines out towards the top and bottom edges of the region.
            val edge = size * 1.8f
            val edgeFade = min(
                ((y + box.height * 0.5f - region.top) / edge).coerceIn(0f, 1f),
                ((region.bottom - (y + box.height * 0.5f)) / edge).coerceIn(0f, 1f),
            )
            if (edgeFade <= 0.01f) continue

            when {
                box.isInterlude -> drawInterlude(canvas, box, region.left, y, pos, activeness[i] * edgeFade, timeSec)
                box.line == null -> drawPlainLine(canvas, box, region.left, y, edgeFade)
                else -> drawSungLine(canvas, box, region.left, y, pos, i, active, edgeFade)
            }
        }
        canvas.restore()
        lyricPaint.shader = null
    }

    private fun activeIndex(lines: List<LyricLine>, pos: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= pos) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** Critically damped springs; lines below the active one get softer springs so they trail. */
    private fun stepSprings(target: Float, active: Int, dt: Float) {
        if (dt <= 0f) return
        val steps = max(1, (dt / (1f / 120f)).toInt())
        val h = dt / steps
        val act = 1f - exp(-dt * 8f)
        for (i in offset.indices) {
            val behind = if (active >= 0) max(0, i - active) else i
            val omega = max(4.5f, 11f / (1f + 0.22f * behind))
            var x = offset[i]
            var v = velocity[i]
            repeat(steps) {
                val a = -omega * omega * (x - target) - 2f * omega * v
                v += a * h
                x += v * h
            }
            offset[i] = x
            velocity[i] = v
            val goal = if (i == active) 1f else 0f
            activeness[i] += (goal - activeness[i]) * act
        }
    }

    private fun drawSungLine(
        canvas: Canvas,
        box: LineBox,
        left: Float,
        top: Float,
        pos: Long,
        index: Int,
        active: Int,
        edgeFade: Float,
    ) {
        val a = activeness[index]
        val line = box.line as LyricLine.Sung
        val distance = if (active < 0) index + 1 else abs(index - active)
        val idleAlpha = (if (index < active) 0.30f else 0.42f) * (1f - min(0.5f, distance * 0.07f))

        // Active line is full size, the rest slightly smaller (scaled around the line's left edge).
        val scale = 0.94f + 0.06f * a
        canvas.save()
        canvas.scale(scale, scale, left, top)

        lyricPaint.textSize = textSize
        val ascent = -lyricPaint.ascent()
        val lift = textSize * 0.05f

        for (w in box.words) {
            val x = left + w.x
            val baseline = top + w.row * rowHeight + ascent
            // Word-by-word fill only with real word timing; otherwise the whole line at once.
            val p = if (line.hasRealWordTiming) progress(pos, w.startMs, w.endMs)
            else if (pos >= line.startMs) 1f else 0f

            // Sung words of the active line rise a touch, like Apple Music.
            val dy = -lift * a * easeOut(p)

            val sungAlpha = idleAlpha + (1f - idleAlpha) * a
            val unsungAlpha = idleAlpha + (0.38f - idleAlpha).coerceAtLeast(0f) * a

            if (a > 0.02f && p > 0f && p < 1f) {
                // Word being sung: gradient wipe with a soft edge.
                val soft = 0.25f
                val f = p * (1f + soft)
                val c0 = withAlpha(Color.WHITE, sungAlpha * edgeFade)
                val c1 = withAlpha(tint, unsungAlpha * edgeFade)
                lyricPaint.shader = LinearGradient(
                    x, 0f, x + w.width, 0f,
                    intArrayOf(c0, c0, c1, c1),
                    floatArrayOf(0f, (f - soft).coerceIn(0f, 1f), f.coerceIn(0f, 1f), 1f),
                    Shader.TileMode.CLAMP,
                )
                lyricPaint.color = Color.WHITE
                canvas.drawText(w.text, x, baseline + dy, lyricPaint)
                lyricPaint.shader = null
            } else {
                lyricPaint.shader = null
                // Sung words are pure white; everything else carries a hint of the cover's colour.
                val alpha = if (p >= 1f) sungAlpha else if (a > 0.02f) unsungAlpha else idleAlpha
                val base = if (p >= 1f && a > 0.02f) ArtTheme.mix(tint, Color.WHITE, a) else tint
                lyricPaint.color = withAlpha(base, alpha * edgeFade)
                canvas.drawText(w.text, x, baseline + dy, lyricPaint)
            }
        }
        canvas.restore()
    }

    private fun drawPlainLine(canvas: Canvas, box: LineBox, left: Float, top: Float, edgeFade: Float) {
        lyricPaint.shader = null
        lyricPaint.textSize = textSize
        lyricPaint.color = withAlpha(tint, 0.85f * edgeFade)
        val ascent = -lyricPaint.ascent()
        for (w in box.words) {
            canvas.drawText(w.text, left + w.x, top + w.row * rowHeight + ascent, lyricPaint)
        }
    }

    private fun drawInterlude(canvas: Canvas, box: LineBox, left: Float, top: Float, pos: Long, visibility: Float, timeSec: Double) {
        if (visibility <= 0.02f) return
        val line = box.line ?: return
        val p = progress(pos, line.startMs, line.endMs)
        val remaining = line.endMs - pos
        // Breathe while waiting, then shrink away just before the next line starts.
        val breathe = 1f + 0.12f * sin(timeSec * 2 * PI / 1.8).toFloat()
        val outro = (remaining / 450f).coerceIn(0f, 1f)
        val r = textSize * 0.13f * breathe * outro
        val cy = top + box.height * 0.5f
        for (k in 0 until 3) {
            val fill = (p * 3f - k).coerceIn(0f, 1f)
            dotPaint.color = withAlpha(ArtTheme.mix(tint, Color.WHITE, fill), (0.3f + 0.7f * fill) * visibility)
            canvas.drawCircle(left + r + k * textSize * 0.42f, cy, r, dotPaint)
        }
    }

    private fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
}
