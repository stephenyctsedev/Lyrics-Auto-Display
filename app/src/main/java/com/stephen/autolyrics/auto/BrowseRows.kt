package com.stephen.autolyrics.auto

import com.stephen.autolyrics.lyrics.LyricLine
import com.stephen.autolyrics.lyrics.LyricsFeedState
import com.stephen.autolyrics.lyrics.message

/** 車機 browse list 一行。id 要穩定 —— host 靠佢認返同一行。 */
data class BrowseRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)

/**
 * 由一份 [LyricsFeedState] 砌出車機要顯示嗰幾行。
 *
 * 抽咗出嚟做純函數（唔掂 Context、唔掂 MediaBrowserService），因為顯示邏輯
 * ——「填得滿幾多行」、「搵唔到歌詞講咩」——正正就係最需要驗證嘅嘢，而喺
 * service 入面就要開部機先試到。
 *
 * ## 排序 = 截走都唔痛
 *
 * Host 可能會將我哋出嘅行截短（見 [LyricsBrowserService.rowBudget]）。所以砌嘅
 * 次序係按重要性嚟排：
 *
 *   1. 歌曲資訊（歌名 + 歌手）
 *   2. 前一句
 *   3. ▶ 當前句
 *   4…  之後嘅句子
 *
 * 咁樣就算真係畀人由尾切走，剩低嘅一定仲有當前句。
 */
object BrowseRows {

    fun build(state: LyricsFeedState, budget: Int): List<BrowseRow> {
        val rows = budget.coerceAtLeast(1)

        val playing = state.nowPlaying
            ?: return listOf(BrowseRow(ID_NO_MEDIA, "冇偵測到播放中嘅音樂"))

        val out = mutableListOf(BrowseRow(ID_TRACK, playing.title, playing.artist))

        val lyrics = state.lyrics
        if (lyrics == null || lyrics.isEmpty) {
            // 舊版呢度淨係出返個歌名就算，結果實機上「查緊」「搵唔到」「連唔到網」
            // 三種情況一模一樣，睇落好似 app 死咗。而家講明係邊一種。
            out.add(BrowseRow(ID_STATUS, state.status.message()))
        } else {
            window(lyrics.lines, state.currentLine, rows - out.size).forEach { (index, text) ->
                out.add(
                    BrowseRow(
                        id = "line_$index",
                        title = if (index == state.currentLine) "▶ $text" else text,
                    )
                )
            }
        }

        return out.take(rows)
    }

    /**
     * 當前句前後嘅一段：前 [LEAD_IN] 句 + 當前句 + 之後填滿 [size]。
     *
     * 兩個夾邊界：
     * - 開頭（currentLine 係 0 或 null）冇「前一句」，窗口由 0 開始。
     * - 埋尾唔夠 [size] 句喺後面嘅時候，起點會向前推返，等最後幾句都仲係
     *   出滿成屏 —— 唔係嘅話首歌播到尾會逐行縮水，下面吊住一大片空白。
     */
    private fun window(
        lines: List<LyricLine>,
        currentLine: Int?,
        size: Int,
    ): List<Pair<Int, String>> {
        if (size <= 0 || lines.isEmpty()) return emptyList()
        val current = currentLine ?: 0
        val lastStart = (lines.size - size).coerceAtLeast(0)
        val start = (current - LEAD_IN).coerceIn(0, lastStart)
        val end = (start + size).coerceAtMost(lines.size)
        // 空行係間奏，出個音符符號 —— 車機唔會畫一行完全空白嘅嘢，
        // 而且留白會令人以為歌詞斷咗。
        return (start until end).map { it to lines[it].text.ifBlank { "♪" } }
    }

    const val ID_NO_MEDIA = "no_media"
    const val ID_TRACK = "track"
    const val ID_STATUS = "status"

    /** 當前句上面留返幾多句做上文。 */
    private const val LEAD_IN = 1
}
