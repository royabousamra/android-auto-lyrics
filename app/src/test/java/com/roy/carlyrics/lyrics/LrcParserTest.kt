package com.roy.carlyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    private fun sung(l: SyncedLyrics) = l.lines.filterIsInstance<LyricLine.Sung>()

    @Test
    fun parsesLineTimestamps() {
        val lrc = """
            [ar:Someone]
            [00:01.00]Hello world
            [00:03.50]Second line
            [00:05.123]Third
        """.trimIndent()
        val lines = sung(LrcParser.parse(lrc))
        assertEquals(listOf(1_000L, 3_500L, 5_123L), lines.map { it.startMs })
        assertEquals("Hello world", lines[0].text)
        assertEquals(listOf("Hello ", "world"), lines[0].words.map { it.text })
        assertFalse(lines[0].hasRealWordTiming)
    }

    @Test
    fun lineLevelLyricsHaveNoEstimatedWordTiming() {
        val lines = sung(LrcParser.parse("[00:10.00]one two three four\n[00:20.00]next"))
        val l = lines[0]
        assertFalse(l.hasRealWordTiming)
        assertEquals(listOf("one ", "two ", "three ", "four"), l.words.map { it.text })
        // Every word shares the line's start, so the whole line lights up together.
        assertTrue(l.words.all { it.startMs == 10_000L && it.endMs == 10_000L })
    }

    @Test
    fun repeatedTagsProduceOneLineEach() {
        val lines = sung(LrcParser.parse("[00:30.00][00:10.00]Chorus\n[00:20.00]Verse"))
        assertEquals(listOf("Chorus", "Verse", "Chorus"), lines.map { it.text })
        assertEquals(listOf(10_000L, 20_000L, 30_000L), lines.map { it.startMs })
    }

    @Test
    fun appliesOffsetTag() {
        val lines = sung(LrcParser.parse("[offset:500]\n[00:02.00]Early"))
        assertEquals(1_500L, lines[0].startMs)
    }

    @Test
    fun insertsIntroAndGapInterludes() {
        val lyrics = LrcParser.parse("[00:12.00]Hi\n[00:40.00]After a long solo", trackDurationMs = 60_000)
        val kinds = lyrics.lines.map { it::class.simpleName }
        assertEquals(listOf("Interlude", "Sung", "Interlude", "Sung"), kinds)
        assertEquals(0L, lyrics.lines[0].startMs)
        assertEquals(12_000L, lyrics.lines[0].endMs)
        assertEquals(40_000L, lyrics.lines[2].endMs)
    }

    @Test
    fun blankLinesAreSkipped() {
        val lines = sung(LrcParser.parse("[00:01.00]A\n[00:02.00]\n[00:03.00]B"))
        assertEquals(listOf("A", "B"), lines.map { it.text })
    }

    @Test
    fun parsesEnhancedWordTags() {
        val lines = sung(LrcParser.parse("[00:10.00]<00:10.00>Hel<00:10.40>lo <00:10.80>world<00:11.50>"))
        val l = lines.single()
        assertTrue(l.hasRealWordTiming)
        assertEquals("Hello world", l.text)
        assertEquals(listOf("Hel", "lo ", "world"), l.words.map { it.text })
        assertEquals(listOf(10_000L, 10_400L, 10_800L), l.words.map { it.startMs })
        assertEquals(11_500L, l.words.last().endMs)
    }

    @Test
    fun activeIndexFindsCurrentLine() {
        val lyrics = LrcParser.parse("[00:01.00]A\n[00:02.00]B\n[00:03.00]C")
        assertEquals(-1, lyrics.activeIndex(500))
        assertEquals(0, lyrics.activeIndex(1_000))
        assertEquals(1, lyrics.activeIndex(2_999))
        assertEquals(2, lyrics.activeIndex(99_000))
    }

    @Test
    fun tokenizesCjkPerCharacter() {
        assertEquals(listOf("夜", "に", "駆", "け", "る"), LrcParser.tokenize("夜に駆ける"))
        assertEquals(listOf("hi ", "there"), LrcParser.tokenize("hi there"))
    }
}
