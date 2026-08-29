package com.stephen.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsSyncTest {

    private fun lyricsOf(vararg times: Long) =
        ParsedLyrics(times.mapIndexed { i, t -> LyricLine(t, "line $i") })

    @Test
    fun `returns null before the first line`() {
        assertNull(LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 1_000))
    }

    @Test
    fun `returns first line exactly on its timestamp`() {
        assertEquals(0, LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 5_000))
    }

    @Test
    fun `returns the line whose window contains the position`() {
        assertEquals(0, LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 7_000))
        assertEquals(1, LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 10_500))
    }

    @Test
    fun `holds the last line after it starts`() {
        assertEquals(1, LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 9_999_000))
    }

    @Test
    fun `returns null for empty lyrics`() {
        assertNull(LyricsSync.currentLineIndex(ParsedLyrics(emptyList()), 1_000))
    }

    @Test
    fun `handles single line lyrics`() {
        assertNull(LyricsSync.currentLineIndex(lyricsOf(5_000), 4_999))
        assertEquals(0, LyricsSync.currentLineIndex(lyricsOf(5_000), 5_000))
    }

    @Test
    fun `returns the last of duplicate timestamps`() {
        val lyrics = ParsedLyrics(listOf(
            LyricLine(5_000, "line a"),
            LyricLine(5_000, "line b"),
            LyricLine(9_000, "line c"),
        ))
        assertEquals(1, LyricsSync.currentLineIndex(lyrics, 5_000))
    }

    @Test
    fun `handles negative position defensively`() {
        assertNull(LyricsSync.currentLineIndex(lyricsOf(0, 5_000), -100))
    }

    @Test
    fun `handles a line at time zero`() {
        assertEquals(0, LyricsSync.currentLineIndex(lyricsOf(0, 5_000), 0))
    }
}
