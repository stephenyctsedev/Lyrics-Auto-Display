package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.ParsedLyrics
import com.stephen.autolyrics.lyrics.TrackKey

enum class LyricsOrigin { MEMORY, DATABASE, NETWORK }

sealed interface LyricsResult {
    /** 搵到有時間戳嘅歌詞。 */
    data class Found(val lyrics: ParsedLyrics, val origin: LyricsOrigin) : LyricsResult

    /** 明確搵唔到（LRCLIB 答 404、或者只有純文字／純音樂）。可以 negative cache。 */
    data object NotFound : LyricsResult

    /** 暫時性失敗（網絡、503、格式壞）。**唔可以** negative cache。 */
    data class Error(val reason: String) : LyricsResult
}

interface LyricsSource {
    suspend fun lookup(key: TrackKey): LyricsResult
}
