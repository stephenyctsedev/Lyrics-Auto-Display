package com.stephen.autolyrics.auto

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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
        scope.launch {
            feed.state.collect { notifyChildrenChanged(ROOT_ID) }
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
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
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
            feed.window(WINDOW_SIZE).forEach { (index, text) ->
                val display = text.ifBlank { " " }
                items.add(
                    textItem(
                        id = "line_$index",
                        title = if (index == state.currentLine) "▶ $display" else display,
                    )
                )
            }
        }

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
        const val ROOT_ID = "root"
        const val WINDOW_SIZE = 4
    }
}
