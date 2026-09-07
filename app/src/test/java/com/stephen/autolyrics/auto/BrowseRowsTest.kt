package com.stephen.autolyrics.auto

import com.stephen.autolyrics.lyrics.LyricLine
import com.stephen.autolyrics.lyrics.LyricsFeedState
import com.stephen.autolyrics.lyrics.LyricsStatus
import com.stephen.autolyrics.lyrics.ParsedLyrics
import com.stephen.autolyrics.media.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseRowsTest {

    private val playing = PlaybackState(
        title = "街角小店",
        artist = "測試歌手",
        album = null,
        positionMs = 0,
        positionUpdateTimeMs = 0,
        playbackSpeed = 1f,
        isPlaying = true,
    )

    private fun lyricsOf(count: Int) =
        ParsedLyrics((0 until count).map { LyricLine(it * 1_000L, "line $it") })

    private fun playingState(
        lyrics: ParsedLyrics? = null,
        currentLine: Int? = null,
        status: LyricsStatus = LyricsStatus.FOUND,
    ) = LyricsFeedState(
        nowPlaying = playing,
        lyrics = lyrics,
        currentLine = currentLine,
        status = status,
    )

    @Test
    fun `no media gives a single row`() {
        val rows = BrowseRows.build(LyricsFeedState(), 7)
        assertEquals(1, rows.size)
        assertEquals(BrowseRows.ID_NO_MEDIA, rows[0].id)
    }

    @Test
    fun `first row is always the track info`() {
        val rows = BrowseRows.build(playingState(lyricsOf(20), currentLine = 5), 7)
        assertEquals(BrowseRows.ID_TRACK, rows[0].id)
        assertEquals("街角小店", rows[0].title)
        assertEquals("測試歌手", rows[0].subtitle)
    }

    @Test
    fun `fills the whole budget with track info plus lyrics`() {
        val rows = BrowseRows.build(playingState(lyricsOf(20), currentLine = 5), 7)
        assertEquals(7, rows.size)
        // 資訊行 + 前一句 + 當前句 + 之後四句
        assertEquals(listOf("track", "line_4", "line_5", "line_6", "line_7", "line_8", "line_9"),
            rows.map { it.id })
    }

    @Test
    fun `marks the current line`() {
        val rows = BrowseRows.build(playingState(lyricsOf(20), currentLine = 5), 7)
        assertEquals("▶ line 5", rows.single { it.id == "line_5" }.title)
        assertTrue(rows.filter { it.title.startsWith("▶") }.size == 1)
    }

    @Test
    fun `keeps the current line when the host truncates from the tail`() {
        // 就算 host 淨係收頭四行，當前句都仲喺入面 —— 呢個就係排序嘅重點。
        val rows = BrowseRows.build(playingState(lyricsOf(20), currentLine = 5), 7).take(4)
        assertTrue(rows.any { it.title.startsWith("▶") })
    }

    @Test
    fun `starts from the top before the first line`() {
        val rows = BrowseRows.build(playingState(lyricsOf(20), currentLine = null), 7)
        assertEquals(listOf("track", "line_0", "line_1", "line_2", "line_3", "line_4", "line_5"),
            rows.map { it.id })
    }

    @Test
    fun `still fills the screen on the last line of the song`() {
        // 埋尾唔好縮水 —— 起點向前推，最後一句都仲係出滿成屏。
        val rows = BrowseRows.build(playingState(lyricsOf(8), currentLine = 7), 7)
        assertEquals(7, rows.size)
        assertEquals(listOf("track", "line_2", "line_3", "line_4", "line_5", "line_6", "line_7"),
            rows.map { it.id })
        assertEquals("▶ line 7", rows.last().title)
    }

    @Test
    fun `short songs just show what they have`() {
        val rows = BrowseRows.build(playingState(lyricsOf(2), currentLine = 0), 7)
        assertEquals(listOf("track", "line_0", "line_1"), rows.map { it.id })
    }

    @Test
    fun `blank lyric lines become a music note`() {
        val lyrics = ParsedLyrics(listOf(LyricLine(0, "a"), LyricLine(1_000, "   ")))
        val rows = BrowseRows.build(playingState(lyrics, currentLine = 0), 7)
        assertEquals("♪", rows.last().title)
    }

    @Test
    fun `says it is still looking while the lookup runs`() {
        val rows = BrowseRows.build(playingState(status = LyricsStatus.LOADING), 7)
        assertEquals(listOf(BrowseRows.ID_TRACK, BrowseRows.ID_STATUS), rows.map { it.id })
        assertEquals("搵緊歌詞…", rows[1].title)
    }

    @Test
    fun `says lyrics were not found instead of showing only the title`() {
        val rows = BrowseRows.build(playingState(status = LyricsStatus.NOT_FOUND), 7)
        assertEquals(2, rows.size)
        assertEquals("搵唔到呢首歌嘅同步歌詞", rows[1].title)
    }

    @Test
    fun `distinguishes a network failure from a missing song`() {
        val rows = BrowseRows.build(playingState(status = LyricsStatus.ERROR), 7)
        assertEquals("連唔到歌詞伺服器，等陣再試", rows[1].title)
    }

    @Test
    fun `empty lyrics fall back to the status row`() {
        val rows = BrowseRows.build(
            playingState(ParsedLyrics(emptyList()), status = LyricsStatus.NOT_FOUND),
            7,
        )
        assertEquals(listOf(BrowseRows.ID_TRACK, BrowseRows.ID_STATUS), rows.map { it.id })
    }

    @Test
    fun `never exceeds the budget`() {
        assertEquals(3, BrowseRows.build(playingState(lyricsOf(20), currentLine = 5), 3).size)
        assertEquals(1, BrowseRows.build(playingState(lyricsOf(20), currentLine = 5), 1).size)
        // budget 0 都要出返個歌名，唔好出一張白紙
        assertEquals(1, BrowseRows.build(playingState(lyricsOf(20), currentLine = 5), 0).size)
    }
}
