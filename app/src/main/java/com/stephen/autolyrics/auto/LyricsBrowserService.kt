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

    /** Host 喺 onGetRoot 講低嘅 root 層規格 —— 見嗰度嘅註釋。 */
    private var rootChildrenLimit = WINDOW_SIZE
    private var rootItemFlag = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE

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
        // 實測過 host 收到 notify 之後即刻會再 onLoadChildren 一次，
        // 所以歌詞真係跟住郁，唔係一張定咗嘅快照。
        scope.launch {
            feed.state.collect { state ->
                Log.i(TAG, "notify line=${state.currentLine}")
                notifyChildrenChanged(ROOT_ID)
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

        // Host 喺 rootHints 講明佢想點畫 root 呢一層 —— 主畫面左邊個 rail
        // 就係攞 root children 嚟鋪，唔係另外一個 "suggested" root。
        // （實測過：Android Auto 由頭到尾都冇傳過 EXTRA_SUGGESTED。）
        //
        //   KEY_ROOT_CHILDREN_LIMIT         最多收幾多個 item
        //   KEY_ROOT_CHILDREN_SUPPORTED_FLAGS  收邊種 flag（1=BROWSABLE 2=PLAYABLE）
        //
        // 兩樣都要跟。出多過 limit 會畀 host 截走，而截嘅係尾嗰幾行 ——
        // 當前歌詞就唔一定仲喺入面。
        rootChildrenLimit = rootHints
            ?.getInt(KEY_ROOT_CHILDREN_LIMIT, WINDOW_SIZE)
            ?.takeIf { it > 0 }
            ?: WINDOW_SIZE

        // Host 唔收 PLAYABLE 嘅話就要出 BROWSABLE，否則啲 item 會靜靜被丟走。
        // 冇講就當兩種都收，照用 PLAYABLE。
        val supportedFlags = rootHints?.getInt(KEY_ROOT_CHILDREN_SUPPORTED_FLAGS, 0) ?: 0
        rootItemFlag = when {
            supportedFlags == 0 -> MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            supportedFlags and MediaBrowserCompat.MediaItem.FLAG_PLAYABLE != 0 ->
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            else -> MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        }

        Log.i(TAG, "onGetRoot from=$clientPackageName limit=$rootChildrenLimit " +
            "flag=$rootItemFlag hints=${rootHints.describe()}")

        return BrowserRoot(ROOT_ID, contentStyleExtras())
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
        val window = rootChildrenLimit
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

        Log.i(TAG, "onLoadChildren parent=$parentId → ${items.size} item(s) flag=$rootItemFlag")

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
        // Flag 由 host 話事 —— Android Auto 主畫面嗰層淨係收 BROWSABLE，
        // 用錯 flag 啲 item 唔會報錯，係靜靜唔見咗。
        return MediaBrowserCompat.MediaItem(desc, rootItemFlag)
    }

    private companion object {
        /** `adb logcat -s AutoLyricsBrowser` 就淨係見到呢個 service 嘅嘢。 */
        const val TAG = "AutoLyricsBrowser"

        const val ROOT_ID = "root"

        /** Host 冇講 limit 嗰陣先用 —— 實際上 Android Auto 一定會講。 */
        const val WINDOW_SIZE = 4

        // Host 喺 rootHints 用嘅 key。androidx.media 冇出 constant，
        // 要自己寫死。
        const val KEY_ROOT_CHILDREN_LIMIT =
            "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT"
        const val KEY_ROOT_CHILDREN_SUPPORTED_FLAGS =
            "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_SUPPORTED_FLAGS"

        // Android Auto content style extras。同上，要自己寫死條 key。
        const val CONTENT_STYLE_SUPPORTED =
            "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val CONTENT_STYLE_PLAYABLE_HINT =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_BROWSABLE_HINT =
            "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_LIST_ITEM = 1
    }
}
