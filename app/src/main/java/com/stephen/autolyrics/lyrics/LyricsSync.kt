package com.stephen.autolyrics.lyrics

/**
 * 純函數，零 Android 依賴：畀歌詞同當前毫秒數，答而家係第幾行。
 * 回傳 null = 第一句仲未到（或者冇歌詞）。
 */
object LyricsSync {

    fun currentLineIndex(lyrics: ParsedLyrics, positionMs: Long): Int? {
        val lines = lyrics.lines
        if (lines.isEmpty()) return null
        if (positionMs < lines.first().timeMs) return null

        // 搵最後一個 timeMs <= positionMs 嘅 index
        var low = 0
        var high = lines.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
