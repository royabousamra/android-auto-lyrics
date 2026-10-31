package com.roy.carlyrics.lyrics

/**
 * Parses LRC lyrics into [SyncedLyrics].
 *
 * Supports:
 *  - standard line tags:          [01:23.45] text
 *  - several tags on one line:    [00:10.00][01:10.00] chorus
 *  - enhanced (word-level) tags:  [00:10.00]<00:10.00>Hel<00:10.40>lo <00:10.80>world<00:11.50>
 *  - [offset:+/-ms]
 *
 * Lyrics are highlighted line by line. Word-by-word timing is used only when the lyrics really
 * contain it (enhanced LRC word tags); it is never estimated.
 */
object LrcParser {

    private val LINE_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** Rough singing speed used to estimate how long a line is actually sung. */
    private const val MS_PER_CHAR = 75L
    private const val MIN_SUNG_MS = 1_200L
    private const val MAX_SUNG_MS = 10_000L

    /** Gaps at least this long (after the estimated end of a line) become an interlude. */
    const val INTERLUDE_MIN_GAP_MS = 7_000L

    /** An intro longer than this gets an interlude before the first line. */
    const val INTRO_MIN_MS = 5_000L

    private data class RawLine(val timeMs: Long, val body: String)

    fun parse(lrc: String, trackDurationMs: Long = 0L): SyncedLyrics {
        var offset = 0L
        val raw = mutableListOf<RawLine>()

        for (line in lrc.lineSequence()) {
            OFFSET_TAG.find(line)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L }

            // Collect the leading run of [mm:ss.xx] tags.
            var rest = line.trim()
            val times = mutableListOf<Long>()
            while (true) {
                val m = LINE_TAG.matchAt(rest, 0) ?: break
                times += tagToMs(m)
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue
            for (t in times) raw += RawLine(t, rest)
        }

        // Positive offset makes lyrics appear sooner (LRC convention).
        val sorted = raw
            .map { it.copy(timeMs = (it.timeMs - offset).coerceAtLeast(0L)) }
            .sortedBy { it.timeMs }

        return SyncedLyrics(buildLines(sorted, trackDurationMs))
    }

    private fun buildLines(raw: List<RawLine>, trackDurationMs: Long): List<LyricLine> {
        val out = mutableListOf<LyricLine>()

        for ((i, r) in raw.withIndex()) {
            val plain = WORD_TAG.replace(r.body, "").trim()
            if (plain.isEmpty()) continue // blank lines only mark the end of the previous line

            val nextStart = raw.getOrNull(i + 1)?.timeMs
                ?: if (trackDurationMs > r.timeMs) trackDurationMs else r.timeMs + MAX_SUNG_MS

            val realWords = parseWordTags(r.body, r.timeMs)
            val estimatedSungMs = (plain.length * MS_PER_CHAR + 400).coerceIn(MIN_SUNG_MS, MAX_SUNG_MS)
            val sungEnd = if (realWords != null) {
                realWords.last().endMs
            } else {
                minOf(nextStart, r.timeMs + estimatedSungMs)
            }

            val words = realWords ?: lineWords(plain, r.timeMs)

            if (out.isEmpty() && r.timeMs >= INTRO_MIN_MS) {
                out += LyricLine.Interlude(startMs = 0L, endMs = r.timeMs)
            }

            val hasGapAfter = raw.getOrNull(i + 1) != null && nextStart - sungEnd >= INTERLUDE_MIN_GAP_MS
            val lineEnd = if (hasGapAfter) sungEnd + 1_000L else nextStart

            out += LyricLine.Sung(
                startMs = r.timeMs,
                endMs = lineEnd,
                text = plain,
                words = words,
                hasRealWordTiming = realWords != null,
            )

            if (hasGapAfter) {
                out += LyricLine.Interlude(startMs = lineEnd, endMs = nextStart)
            }
        }
        return out
    }

    /** Returns words from enhanced LRC tags, or null if the line has none. */
    private fun parseWordTags(body: String, lineStart: Long): List<Word>? {
        val tags = WORD_TAG.findAll(body).toList()
        if (tags.isEmpty()) return null

        val segments = mutableListOf<Pair<Long, String>>()
        // Text before the first tag (rare) starts at the line time.
        val lead = body.substring(0, tags.first().range.first)
        if (lead.isNotBlank()) segments += lineStart to lead
        for ((i, tag) in tags.withIndex()) {
            val textEnd = tags.getOrNull(i + 1)?.range?.first ?: body.length
            segments += tagToMs(tag) to body.substring(tag.range.last + 1, textEnd)
        }

        val words = mutableListOf<Word>()
        for ((i, seg) in segments.withIndex()) {
            val (start, text) = seg
            if (text.isEmpty()) continue // a trailing tag only marks the end of the last word
            val end = segments.getOrNull(i + 1)?.first ?: (start + 600L)
            words += Word(text = text, startMs = start, endMs = maxOf(end, start))
        }
        if (words.isEmpty()) return null
        // Trim stray leading/trailing whitespace on the line edges.
        words[0] = words[0].copy(text = words[0].text.trimStart())
        words[words.lastIndex] = words.last().copy(text = words.last().text.trimEnd())
        return words
    }

    /**
     * Words of a line-level line. They are only layout tokens for word wrapping: all of them
     * carry the line's start time, so the whole line lights up at once.
     */
    fun lineWords(text: String, startMs: Long): List<Word> =
        tokenize(text).map { Word(it, startMs, startMs) }

    /**
     * Splits a line into display tokens. Each token keeps its trailing whitespace so the renderer
     * can place tokens back to back. Scripts written without spaces (CJK) are split per character.
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                isCjk(c) -> {
                    if (sb.isNotEmpty()) { tokens += sb.toString(); sb.clear() }
                    sb.append(c)
                    // Attach any following whitespace / punctuation to this character.
                    while (i + 1 < text.length && (text[i + 1].isWhitespace() || isClosingPunct(text[i + 1]))) {
                        sb.append(text[++i])
                    }
                    tokens += sb.toString(); sb.clear()
                }
                c.isWhitespace() -> {
                    sb.append(c)
                    while (i + 1 < text.length && text[i + 1].isWhitespace()) sb.append(text[++i])
                    tokens += sb.toString(); sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) tokens += sb.toString()
        return tokens.filter { it.isNotBlank() }
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
    }

    private fun isClosingPunct(c: Char) = c in "、。，．！？」』）,.!?)"

    private fun tagToMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min * 60_000 + sec * 1_000 + fracMs
    }
}
