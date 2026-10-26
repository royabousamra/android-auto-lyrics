package com.roy.carlyrics.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
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
 *  - large heavy left-aligned text on an album-tinted gradient
 *  - the active line is bright and full size; others are dimmed and slightly smaller
 *  - words fill left-to-right with a soft edge and lift slightly as they are sung
 *  - lines scroll on springs, lines further down lag a little (the "cascade")
 *  - instrumental gaps show three breathing dots that fill up over the gap
 *
 * Not thread safe: call [draw] from one thread.
 */
class KaraokeRenderer {

    data class Frame(
        val nowPlaying: NowPlaying?,
        val lyrics: LyricsState,
        /** Playback position already corrected by the user offset. */
        val positionMs: Long,
    )

    // ---- Paints ----

    private val heavy: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 800, false)
        else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private val lyricPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = heavy
        color = Color.WHITE
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.WHITE
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bgPaint = Paint()
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artClip = Path()
    private val artRect = RectF()

    // ---- Background colour animation ----

    private var artRef: Bitmap? = null
    private var targetColors = ArtColors.DEFAULT
    private val shownColors = ArtColors.DEFAULT.copyOf()
    private var bgShaderKey = ""

    // ---- Layout ----

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

        drawBackground(canvas, area, frame.nowPlaying?.art, dt)
        if (area.width() <= 0 || area.height() <= 0) return

        val pad = min(area.width(), area.height()) * 0.06f
        val baseSize = min(area.height() / 6.5f, area.width() / 13f).coerceAtLeast(18f)
        val headerHeight = drawHeader(canvas, area, pad, baseSize, frame)

        val region = RectF(
            area.left + pad,
            area.top + headerHeight + pad * 0.5f,
            area.right - pad,
            area.bottom - pad * 0.5f,
        )
        if (region.height() < baseSize) return

        when (val s = frame.lyrics) {
            is LyricsState.Synced -> drawLyrics(canvas, region, baseSize, s.lyrics.lines, null, frame, dt)
            is LyricsState.Plain -> drawLyrics(canvas, region, baseSize * 0.8f, null, s.lines, frame, dt)
            LyricsState.NoMusic -> drawMessage(canvas, region, baseSize, "Play something", "Lyrics follow whatever music app is playing")
            is LyricsState.Loading -> drawMessage(canvas, region, baseSize, "Finding lyrics…", null)
            is LyricsState.Instrumental -> drawMessage(canvas, region, baseSize, "♪  Instrumental", null)
            is LyricsState.NotFound -> drawMessage(canvas, region, baseSize, "No lyrics found", "${s.track.artist} – ${s.track.title}")
            is LyricsState.Failed -> drawMessage(canvas, region, baseSize, "Couldn't load lyrics", s.message)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Background

    private fun drawBackground(canvas: Canvas, area: Rect, art: Bitmap?, dt: Float) {
        if (art !== artRef) {
            artRef = art
            targetColors = ArtColors.from(art)
        }
        val k = 1f - exp(-dt * 3f)
        for (i in 0..1) shownColors[i] = ArtColors.lerp(shownColors[i], targetColors[i], k)

        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val key = "${shownColors[0]}|${shownColors[1]}|$w|$h"
        if (key != bgShaderKey) {
            bgShaderKey = key
            bgPaint.shader = LinearGradient(0f, 0f, w * 0.6f, h, shownColors[0], shownColors[1], Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
    }

    // ------------------------------------------------------------------------------------------
    // Header: small cover + title + artist

    private fun drawHeader(canvas: Canvas, area: Rect, pad: Float, baseSize: Float, frame: Frame): Float {
        val np = frame.nowPlaying ?: return pad
        val size = baseSize * 1.1f
        val left = area.left + pad
        val top = area.top + pad * 0.8f
        var textLeft = left

        np.art?.takeIf { !it.isRecycled }?.let { art ->
            artRect.set(left, top, left + size, top + size)
            artClip.reset()
            artClip.addRoundRect(artRect, size * 0.12f, size * 0.12f, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(artClip)
            canvas.drawBitmap(art, null, artRect, artPaint)
            canvas.restore()
            textLeft = left + size + pad * 0.5f
        }

        val avail = area.right - pad - textLeft
        if (avail > 0) {
            titlePaint.textSize = size * 0.42f
            subtitlePaint.textSize = size * 0.36f
            subtitlePaint.alpha = 170
            val title = TextUtils.ellipsize(np.track.title, titlePaint, avail, TextUtils.TruncateAt.END)
            val artist = TextUtils.ellipsize(np.track.artist, subtitlePaint, avail, TextUtils.TruncateAt.END)
            canvas.drawText(title, 0, title.length, textLeft, top + size * 0.45f, titlePaint)
            canvas.drawText(artist, 0, artist.length, textLeft, top + size * 0.92f, subtitlePaint)
        }
        return pad * 0.8f + size
    }

    // ------------------------------------------------------------------------------------------
    // Status messages

    private fun drawMessage(canvas: Canvas, region: RectF, baseSize: Float, title: String, subtitle: String?) {
        lyricPaint.shader = null
        lyricPaint.textSize = baseSize * 0.8f
        lyricPaint.alpha = 235
        val cy = region.centerY()
        canvas.drawText(title, region.left, cy, lyricPaint)
        if (subtitle != null) {
            subtitlePaint.textSize = baseSize * 0.4f
            subtitlePaint.alpha = 160
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
            val p = progress(pos, w.startMs, w.endMs)

            // Sung words of the active line rise a touch, like Apple Music.
            val dy = -lift * a * easeOut(p)

            val sungAlpha = idleAlpha + (1f - idleAlpha) * a
            val unsungAlpha = idleAlpha + (0.38f - idleAlpha).coerceAtLeast(0f) * a

            if (a > 0.02f && p > 0f && p < 1f) {
                // Word being sung: gradient wipe with a soft edge.
                val soft = 0.25f
                val f = p * (1f + soft)
                val c0 = withAlpha(Color.WHITE, sungAlpha * edgeFade)
                val c1 = withAlpha(Color.WHITE, unsungAlpha * edgeFade)
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
                val alpha = if (p >= 1f) sungAlpha else if (a > 0.02f) unsungAlpha else idleAlpha
                lyricPaint.color = withAlpha(Color.WHITE, alpha * edgeFade)
                canvas.drawText(w.text, x, baseline + dy, lyricPaint)
            }
        }
        canvas.restore()
    }

    private fun drawPlainLine(canvas: Canvas, box: LineBox, left: Float, top: Float, edgeFade: Float) {
        lyricPaint.shader = null
        lyricPaint.textSize = textSize
        lyricPaint.color = withAlpha(Color.WHITE, 0.85f * edgeFade)
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
            dotPaint.color = withAlpha(Color.WHITE, (0.3f + 0.7f * fill) * visibility)
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
