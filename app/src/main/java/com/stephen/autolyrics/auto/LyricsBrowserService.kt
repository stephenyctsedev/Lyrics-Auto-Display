package com.stephen.autolyrics.auto

import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.stephen.autolyrics.AppGraph
import com.stephen.autolyrics.data.LyricsResult
import com.stephen.autolyrics.lyrics.LyricsSync
import com.stephen.autolyrics.lyrics.ParsedLyrics
import com.stephen.autolyrics.lyrics.TrackKey
import com.stephen.autolyrics.media.ActiveMediaWatcher
import com.stephen.autolyrics.media.PlaybackState
import com.stephen.autolyrics.media.PositionEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
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
 * 刷新策略同舊 CarLyricsScreen 一樣：250ms tick 推算位置，但**只有當前行號
 * 變咗**先 notify。快歌一句 2 秒 → 每 2 秒先 notify 一次，唔會浪費 host 頻寬。
 */
class LyricsBrowserService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var mediaSession: MediaSessionCompat

    private var nowPlaying: PlaybackState? = null
    private var lyrics: ParsedLyrics? = null
    private var currentLine: Int? = null
    private var lookupJob: Job? = null

    override fun onCreate() {
        super.onCreate()

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

        // 換歌就重新查歌詞
        scope.launch {
            ActiveMediaWatcher.state.collectLatest { state ->
                val changed = state?.title != nowPlaying?.title ||
                    state?.artist != nowPlaying?.artist
                nowPlaying = state

                if (changed) {
                    lyrics = null
                    currentLine = null
                    notifyChildrenChanged(ROOT_ID)
                    if (state != null) startLookup(state)
                }
            }
        }

        // 250ms tick：推算位置，行號變咗先 notify
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val state = nowPlaying ?: continue
                val parsed = lyrics ?: continue
                val position = PositionEstimator.estimate(state, SystemClock.elapsedRealtime())
                val line = LyricsSync.currentLineIndex(parsed, position)
                if (line != currentLine) {
                    currentLine = line
                    notifyChildrenChanged(ROOT_ID)
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun startLookup(state: PlaybackState) {
        lookupJob?.cancel()
        lookupJob = scope.launch {
            val result = AppGraph.repository(this@LyricsBrowserService)
                .lookup(TrackKey(state.title, state.artist))
            // 查完之後首歌可能已經換咗 —— 要同時比對歌手，因為唔同歌手可以有同名歌
            if (state.title != nowPlaying?.title || state.artist != nowPlaying?.artist) {
                return@launch
            }
            lyrics = (result as? LyricsResult.Found)?.lyrics
            currentLine = null
            notifyChildrenChanged(ROOT_ID)
        }
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
        val state = nowPlaying
        val parsed = lyrics

        if (state == null) {
            items.add(textItem("no_media", "冇偵測到播放中嘅音樂"))
        } else if (parsed == null || parsed.isEmpty) {
            // 靜靜降級：搵唔到 / 未查完 / 網絡唔通，一律顯示歌名歌手，唔出 error。
            // 行車時閃動嘅錯誤訊息係安全問題，而且司機都做唔到啲咩。
            items.add(textItem("track", state.title, state.artist))
        } else {
            visibleWindow(parsed).forEach { (index, text) ->
                val display = text.ifBlank { " " }
                items.add(
                    textItem(
                        id = "line_$index",
                        title = if (index == currentLine) "▶ $display" else display,
                    )
                )
            }
        }

        result.sendResult(items)
    }

    /**
     * 一般情況：前一句 + 當前句 + 後兩句（共 4 行）。
     * 首句附近（currentLine 為 0 或 null）冇「前一句」可以顯示，
     * 窗口會夾到 0 開始，變成當前句 + 後三句。
     */
    private fun visibleWindow(parsed: ParsedLyrics): List<Pair<Int, String>> {
        val lines = parsed.lines
        val current = currentLine ?: 0
        val start = (current - 1).coerceAtLeast(0)
        val end = (start + WINDOW_SIZE).coerceAtMost(lines.size)
        return (start until end).map { it to lines[it].text }
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
        const val TICK_MS = 250L
        const val WINDOW_SIZE = 4
    }
}
