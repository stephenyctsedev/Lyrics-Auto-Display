package com.stephen.autolyrics.lyrics

/**
 * 查歌詞嘅結果狀態。
 *
 * 原本 LyricsFeedState 淨係得一個 `loading: Boolean`，所以「查緊」同「查完但係
 * 冇」喺車機嗰邊睇落一模一樣 —— 兩種情況都係得個歌名喺度，用家唔知係未查完、
 * 搵唔到、定係根本連唔到網。呢個 enum 就係為咗將三者分開講。
 */
enum class LyricsStatus {
    /** 冇歌播緊。 */
    IDLE,

    /** 查緊。 */
    LOADING,

    /** 搵到有時間戳嘅歌詞，可以逐句顯示。 */
    FOUND,

    /** 明確搵唔到：LRCLIB 冇收錄、純音樂、或者只有冇時間戳嘅純文字歌詞。 */
    NOT_FOUND,

    /** 暫時性失敗（網絡唔通、server 503）。同 NOT_FOUND 唔同 —— 遲啲再嚟可能有。 */
    ERROR,
}

/**
 * 冇歌詞行可以畫嗰陣顯示嘅一句嘢。手機同車機共用同一句，唔會兩邊講唔同版本。
 *
 * 車機嗰句係靜態文字，唔會閃、唔會扮 error dialog —— 行車時要睇一眼就明，
 * 而唔係要人分心去處理。
 */
fun LyricsStatus.message(): String = when (this) {
    LyricsStatus.IDLE, LyricsStatus.LOADING -> "搵緊歌詞…"
    // FOUND 但係行到呢度即係 lyrics 空咗（理論上唔會，LyricsFeed 已經將空歌詞
    // 當 NOT_FOUND）。真係發生嘅話，同 NOT_FOUND 講同一句好過扮有嘢顯示。
    LyricsStatus.FOUND, LyricsStatus.NOT_FOUND -> "搵唔到呢首歌嘅同步歌詞"
    LyricsStatus.ERROR -> "連唔到歌詞伺服器，等陣再試"
}
