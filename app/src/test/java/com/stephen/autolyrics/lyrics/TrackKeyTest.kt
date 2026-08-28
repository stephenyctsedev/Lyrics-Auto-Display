package com.stephen.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackKeyTest {

    private fun norm(title: String, artist: String = "Some Artist") =
        TrackKey(title, artist).normalized().title

    @Test
    fun `removes remaster suffix in parentheses`() {
        assertEquals("song name", norm("Song Name (Remastered 2011)"))
    }

    @Test
    fun `removes live suffix after dash`() {
        assertEquals("song name", norm("Song Name - Live"))
    }

    @Test
    fun `removes feat suffix`() {
        assertEquals("song name", norm("Song Name (feat. Other Artist)"))
    }

    @Test
    fun `removes bracketed remix marker`() {
        assertEquals("song name", norm("Song Name [Radio Edit]"))
    }

    @Test
    fun `keeps parentheses that are part of the actual title`() {
        // 括號內容唔喺已知雜訊清單，要保留
        assertEquals("song name (reprise)", norm("Song Name (Reprise)"))
    }

    @Test
    fun `collapses repeated whitespace and lowercases`() {
        assertEquals("song name", norm("  Song   NAME  "))
    }

    @Test
    fun `normalizes the artist too`() {
        val k = TrackKey("Song", "  The ARTIST ").normalized()
        assertEquals("the artist", k.artist)
    }

    @Test
    fun `leaves a clean title unchanged`() {
        assertEquals("song name", norm("Song Name"))
    }

    @Test
    fun `does not strip the whole title when it is only a suffix`() {
        // 全個名都係括號 → 唔應該變空字串
        assertEquals("(remastered 2011)", norm("(Remastered 2011)"))
    }

    @Test
    fun `cacheKey joins artist and title case-insensitively`() {
        assertEquals(
            TrackKey("Song Name", "Artist").cacheKey(),
            TrackKey("song name", "artist").cacheKey(),
        )
    }
}
