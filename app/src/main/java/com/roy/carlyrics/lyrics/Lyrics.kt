package com.roy.carlyrics.lyrics

/**
 * Pure-Kotlin lyrics model. No Android imports here so it can be unit tested on the JVM.
 */

data class Word(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

sealed interface LyricLine {
    val startMs: Long
    val endMs: Long

    /**
     * A sung line. With [hasRealWordTiming] (enhanced LRC) each word has its own timing and the
     * line fills word by word; otherwise every word carries the line's start and the whole line
     * is highlighted at once.
     */
    data class Sung(
        override val startMs: Long,
        override val endMs: Long,
        val text: String,
        val words: List<Word>,
        val hasRealWordTiming: Boolean,
    ) : LyricLine

    /** An instrumental gap, drawn as Apple Music style "• • •". */
    data class Interlude(
        override val startMs: Long,
        override val endMs: Long,
    ) : LyricLine
}

data class SyncedLyrics(val lines: List<LyricLine>) {

    /** Index of the line that is active at [positionMs], or -1 before the first line. */
    fun activeIndex(positionMs: Long): Int {
        // Binary search for the last line whose start <= position.
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}

/** 0..1 progress of [positionMs] through [startMs, endMs]. */
fun progress(positionMs: Long, startMs: Long, endMs: Long): Float {
    if (endMs <= startMs) return if (positionMs >= startMs) 1f else 0f
    return ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}
