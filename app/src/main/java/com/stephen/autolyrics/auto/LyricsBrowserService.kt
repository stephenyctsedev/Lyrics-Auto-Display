package com.stephen.autolyrics.auto

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.stephen.autolyrics.lyrics.LyricsFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Auto 用 MediaBrowserService 呢條路，唔用 CarAppService + template。
 *
 * 點解：Android Auto 淨係接受固定幾個 category 嘅 template app，「歌詞顯示」
 * 唔屬於任何一個。Media 就係官方支援嘅類別。
 *
 * 個 trick 係：將歌詞當「可瀏覽嘅曲目清單」餵畀 host —— onLoadChildren() 回
 * 當前歌詞嘅頭尾幾行做 MediaItem，再靠 notifyChildrenChanged() 叫 host 重新
 * 拉一次，睇落就變咗滾動嘅歌詞。
 *
 * 顯示邏輯由 LyricsFeed 提供 —— 同手機畫面共用同一份 state，所以手機見到咩，
 * 連咗車就同步顯示咩。節流亦喺 LyricsFeed 做（行號冇變就唔出新 state），
 * 快歌一句 2 秒即係每 2 秒先 notify 一次。
 */
class LyricsBrowserService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var feed: LyricsFeed

    override fun onCreate() {
        super.onCreate()

        // 同手機畫面共用同一份邏輯 —— 手機見到咩，車機就顯示咩。
        feed = LyricsFeed(applicationContext, scope).also { it.start() }

        // 淨係用嚟畀 host 知有個 session 喺度 —— 冇 setCallback()，即係唔接
        // play / pause / seek。呢個 app 讀其他播放器嘅狀態，唔控制播放。
        mediaSession = MediaSessionCompat(this, "AutoLyrics").apply {
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(0)
                    .build()
            )
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        // state 一變就叫 host 重新拉 —— LyricsFeed 已經做咗節流
        // （行號冇變就唔會出新 state），所以呢度直接跟住行就得。
        //
        // 兩個 root 都要 notify：主畫面個 rail 訂嘅係 SUGGESTED_ROOT_ID，
        // 淨係 notify ROOT_ID 嘅話，入到 app 見到歌詞會郁，但 rail 上面
        // 嗰幾行會定住唔變。
        scope.launch {
            feed.state.collect { state ->
                // 對住 onLoadChildren 個 log 睇：見到 notify 但之後冇
                // onLoadChildren parent=suggested，即係 host 唔會為住個 rail
                // 重新拉 —— 咁 rail 上面就係一張快照，唔會跟住歌郁。
                Log.i(TAG, "notify line=${state.currentLine}")
                notifyChildrenChanged(ROOT_ID)
                notifyChildrenChanged(SUGGESTED_ROOT_ID)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // 呢個 service 喺 manifest 係 exported=true（Android Auto 要 bind 到），
        // 所以機上任何一個 app 都 bind 得到。冇呢個檢查嘅話，第三方 app 可以
        // 讀到你播緊咩同埋當前歌詞。回 null = 拒絕連接。
        if (!CallerValidator.isAllowed(this, clientPackageName, clientUid)) return null

        // Android Auto 主畫面左邊個 rail 係揭得頁嘅，每頁攞一個 media app 嘅
        // 「suggestion」。Host 攞呢啲內容嗰陣，會喺 rootHints 塞
        // EXTRA_SUGGESTED=true，我哋就回一個唔同嘅 root ——
        // 佢啲 children 就係鋪喺嗰格嘅卡。
        //
        // 唔理 rootHints 嘅話（之前就係咁）host 攞到嘅係普通 browse tree，
        // 個 app 就唔會出現喺主畫面，一定要入 app 先睇到歌詞。
        val suggested = rootHints?.getBoolean(BrowserRoot.EXTRA_SUGGESTED) == true
        val rootId = if (suggested) SUGGESTED_ROOT_ID else ROOT_ID

        // 主畫面 rail 冇嘢出嘅時候，呢行分辨到兩種完全唔同嘅失敗：
        // 見唔到 suggested=true → host 根本冇問過，即係呢條路唔通；
        // 見到 suggested=true 但畫面仍然空 → 佢問咗，係我哋答錯。
        Log.i(TAG, "onGetRoot from=$clientPackageName suggested=$suggested " +
            "root=$rootId hints=${rootHints.describe()}")

        return BrowserRoot(rootId, contentStyleExtras())
    }

    /** Bundle 個預設 toString 淨係印個 object id，冇用，要自己攤開。 */
    @Suppress("DEPRECATION")
    private fun Bundle?.describe(): String {
        if (this == null) return "null"
        return keySet().joinToString(", ", "[", "]") { key -> "$key=${get(key)}" }
    }

    /**
     * 叫 host 用「一行行嘅 list」而唔係「方格」嚟畫。
     *
     * 歌詞係文字，grid 會將每句切到剩返幾個字。呢啲係 Android Auto 嘅
     * de-facto extras（Google 官方 media sample 都係用呢幾條 key），
     * 唔識嘅 host 會直接忽略，所以擺住都安全。
     */
    private fun contentStyleExtras() = Bundle().apply {
        putBoolean(CONTENT_STYLE_SUPPORTED, true)
        putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM)
        putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        // 主畫面個 rail 淨係得幾行位，出多咗會畀 host 直接截走，
        // 而且截嘅係尾嗰幾行 —— 當前歌詞就唔一定喺入面。
        val window =
            if (parentId == SUGGESTED_ROOT_ID) SUGGESTED_WINDOW_SIZE else WINDOW_SIZE

        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        val state = feed.state.value
        val playing = state.nowPlaying

        if (playing == null) {
            items.add(textItem("no_media", "冇偵測到播放中嘅音樂"))
        } else if (state.lyrics == null || state.lyrics.isEmpty) {
            // 靜靜降級：搵唔到 / 未查完 / 網絡唔通，一律顯示歌名歌手，唔出 error。
            // 行車時閃動嘅錯誤訊息係安全問題，而且司機都做唔到啲咩。
            items.add(textItem("track", playing.title, playing.artist))
        } else {
            feed.window(window).forEach { (index, text) ->
                val display = text.ifBlank { " " }
                items.add(
                    textItem(
                        id = "line_$index",
                        title = if (index == state.currentLine) "▶ $display" else display,
                    )
                )
            }
        }

        Log.i(TAG, "onLoadChildren parent=$parentId → ${items.size} item(s)" +
            (if (parentId == SUGGESTED_ROOT_ID) " [主畫面 rail]" else ""))

        result.sendResult(items)
    }

    private fun textItem(
        id: String,
        title: String,
        subtitle: String? = null,
    ): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .build()
        // PLAYABLE 而唔係 BROWSABLE：歌詞行撳落去唔應該再展開一層。
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    private companion object {
        /** `adb logcat -s AutoLyricsBrowser` 就淨係見到呢個 service 嘅嘢。 */
        const val TAG = "AutoLyricsBrowser"

        const val ROOT_ID = "root"

        /** Host 攞主畫面 rail 內容嗰陣用嘅 root —— 見 onGetRoot()。 */
        const val SUGGESTED_ROOT_ID = "suggested"

        const val WINDOW_SIZE = 4
        const val SUGGESTED_WINDOW_SIZE = 3

        // Android Auto content style extras。androidx.media 冇 constant 出，
        // 要自己寫死條 key。
        const val CONTENT_STYLE_SUPPORTED =
            "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val CONTENT_STYLE_PLAYABLE_HINT =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_BROWSABLE_HINT =
            "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_LIST_ITEM = 1
    }
}
