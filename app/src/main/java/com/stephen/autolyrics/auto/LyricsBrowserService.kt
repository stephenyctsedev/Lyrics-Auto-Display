package com.stephen.autolyrics.auto

import android.content.SharedPreferences
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
    private lateinit var carRows: CarRowSettings

    /** Host 喺 onGetRoot 報嘅行數 —— 點樣用，見 [BrowseRows.budget]。 */
    private var rootChildrenLimit = DEFAULT_ROOT_CHILDREN_LIMIT

    /**
     * 喺手機改咗行數／開咗校準，車機即刻跟住變 —— 唔使拔線重連。
     *
     * 一定要擺喺 field：SharedPreferences 只 hold weak reference。
     * scope 係 Dispatchers.Main，所以 notifyChildrenChanged 一定喺 main thread 行。
     */
    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            scope.launch { notifyChildrenChanged(ROOT_ID) }
        }

    override fun onCreate() {
        super.onCreate()

        // 同手機畫面共用同一份邏輯 —— 手機見到咩，車機就顯示咩。
        feed = LyricsFeed(applicationContext, scope).also { it.start() }

        carRows = CarRowSettings(this).also { it.register(settingsListener) }

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
        carRows.unregister(settingsListener)
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

        // Host 喺 rootHints 講低佢想點畫 root 呢一層。只讀 limit 一項，而且
        // 當佢係下限唔係上限 —— 點解咁，見 BrowseRows.budget()。
        rootChildrenLimit = rootHints
            ?.getInt(KEY_ROOT_CHILDREN_LIMIT, DEFAULT_ROOT_CHILDREN_LIMIT)
            ?.takeIf { it > 0 }
            ?: DEFAULT_ROOT_CHILDREN_LIMIT

        // ⚠️ 另一條 hint KEY_ROOT_CHILDREN_SUPPORTED_FLAGS 傳 1（BROWSABLE），
        // 但實測過唔好跟：
        //
        //   跟咗出 BROWSABLE  → 歌詞照顯示，但每行右邊多咗個「入去下一層」
        //                       箭嘴（歌詞根本冇下一層），而且 host 會為咗
        //                       預先展開，逐行嚟問 children，一首歌 25 次。
        //   照出 PLAYABLE    → 歌詞一樣顯示到，冇箭嘴，host 淨係問 root。
        //
        // 即係話嗰條 hint 講嘅係主畫面 rail 嗰層收咩，唔係話唔跟就會丟走。
        // 兩個 build 都喺 DHU 上面行過先落呢個結論。
        Log.i(TAG, "onGetRoot from=$clientPackageName limit=$rootChildrenLimit " +
            "hints=${rootHints.describe()}")

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
        // 淨係 root 有內容。冇呢個 guard 嘅話，任何 parentId 都會攞到同一份
        // 歌詞 —— 即係每一行入面又有成個窗口，一棵無限深嘅樹。用 PLAYABLE 之後
        // host 唔會再問，但佢問唔問係佢話事，唔應該靠佢自律。
        if (parentId != ROOT_ID) {
            Log.i(TAG, "onLoadChildren parent=$parentId → 冇下一層")
            result.sendResult(mutableListOf())
            return
        }

        // 校準模式出間尺，唔出歌詞 —— 目的就係喺車機上面數，所以一定要出到
        // 我哋容許嘅上限，先睇得出佢究竟喺邊行斷。
        val override = carRows.rows
        val budget = BrowseRows.budget(rootChildrenLimit, override)
        val rows =
            if (carRows.calibrating) BrowseRows.ruler()
            else BrowseRows.build(feed.state.value, budget)

        val items = rows.map { textItem(it.id, it.title, it.subtitle) }.toMutableList()

        // `adb logcat -s AutoLyricsBrowser` 見到呢行，就知我哋送咗幾多行出去、
        // host 報幾多、用緊校準值定自動值。（車機實際畫咗幾多行冇得由呢度知 ——
        // 要開校準模式喺螢幕上面數，見 BrowseRows.ruler()。）
        Log.i(TAG, "onLoadChildren parent=$parentId → ${items.size} item(s) " +
            "(budget=$budget limit=$rootChildrenLimit override=$override " +
            "calibrating=${carRows.calibrating})")

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
        // PLAYABLE 而唔係 BROWSABLE：歌詞行冇下一層，唔應該有個箭嘴
        // 引人撳入去。詳細見 onGetRoot() 嗰段實測記錄。
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    private companion object {
        /** `adb logcat -s AutoLyricsBrowser` 就淨係見到呢個 service 嘅嘢。 */
        const val TAG = "AutoLyricsBrowser"

        const val ROOT_ID = "root"

        /** Host 冇報 limit 嗰陣先用 —— 實際上 Android Auto 一定會報。 */
        const val DEFAULT_ROOT_CHILDREN_LIMIT = 4

        // Host 喺 rootHints 用嘅 key。androidx.media 冇出 constant，
        // 要自己寫死。
        const val KEY_ROOT_CHILDREN_LIMIT =
            "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT"

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
