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
 * Host 可能會將我哋出嘅行截短（見 [budget]）。所以砌嘅次序係按重要性嚟排：
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

    /**
     * 一屏出幾多行（連歌曲資訊嗰行）。
     *
     * MediaBrowserService 冇任何 callback 話返畀我哋知 host 實際畫咗幾多行 ——
     * 送咗出去就冇下文。所以呢度得兩個來源：
     *
     * - [override]：校準模式度出嚟嘅數（見 [ruler]）。有嘅話行先，因為佢係
     *   真係喺嗰部車機上面數過嘅。
     * - Host 喺 rootHints 報嘅 `KEY_ROOT_CHILDREN_LIMIT`。**當下限唔當上限** ——
     *   v0.2.3 實機試過跟足佢報嘅數目出，畫面下面吊住一大片空白，即係嗰條
     *   limit 講嘅係佢主畫面 rail 嗰層收幾多，唔係呢一頁畫得落幾多行。
     */
    fun budget(hostLimit: Int, override: Int?): Int =
        override?.coerceIn(MIN_ROWS, MAX_ROWS)
            ?: hostLimit.coerceIn(DESIRED_ROWS, MAX_ROWS)

    /**
     * 校準模式：喺車機出一把間尺 `01`、`02`、`03`…
     *
     * 數到車機顯示到最後一行係邊個號碼，嗰個號碼就係佢一屏容得落幾多行 ——
     * 唔理係螢幕唔夠高、host 截短、定係字太大撐開咗，量到嘅都係最終結果。
     * 之後喺手機入返個數落設定，就真係「顯示到幾多行就出幾多行」。
     */
    fun ruler(count: Int = MAX_ROWS): List<BrowseRow> =
        (1..count.coerceIn(1, MAX_ROWS)).map { n ->
            val label = if (n < 10) "0$n" else "$n"
            BrowseRow(
                id = "ruler_$n",
                title = "$label ──── 校準中",
                // 第一行同真正嘅歌曲資訊行一樣有副標題。有啲 host 將有副標題嘅
                // item 畫高啲，間尺唔跟住嚟就會量多咗一行。
                subtitle = if (n == 1) "數到最後見到嘅號碼，入返落手機" else null,
            )
        }

    const val ID_NO_MEDIA = "no_media"
    const val ID_TRACK = "track"
    const val ID_STATUS = "status"

    /** 冇校準過嗰陣出幾多行：1 行歌曲資訊 + 6 行歌詞。 */
    const val DESIRED_ROWS = 7

    /** 校準到最少／最多幾多行。間尺亦係出到 [MAX_ROWS] 行為止。 */
    const val MIN_ROWS = 2
    const val MAX_ROWS = 12

    /** 當前句上面留返幾多句做上文。 */
    private const val LEAD_IN = 1
}
