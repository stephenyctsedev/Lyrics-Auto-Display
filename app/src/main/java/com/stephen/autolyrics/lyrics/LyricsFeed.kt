package com.stephen.autolyrics.lyrics

import android.content.Context
import android.os.SystemClock
import com.stephen.autolyrics.AppGraph
import com.stephen.autolyrics.data.LyricsResult
import com.stephen.autolyrics.media.ActiveMediaWatcher
import com.stephen.autolyrics.media.PlaybackState
import com.stephen.autolyrics.media.PositionEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 手機畫面同車機共用嘅一份顯示狀態。 */
data class LyricsFeedState(
    val nowPlaying: PlaybackState? = null,
    val lyrics: ParsedLyrics? = null,
    val currentLine: Int? = null,
    /**
     * 查歌詞查成點。手機同車機都會讀 —— 兩邊都要講得出「查緊」同
     * 「查完，冇」嘅分別，唔係就淨係見到個歌名喺度，唔知發生咩事。
     */
    val status: LyricsStatus = LyricsStatus.IDLE,
)

/**
 * 跟住播放狀態查歌詞、推算當前行。
 *
 * 呢個原本淨係喺 LyricsBrowserService 入面做，即係「淨係開手機 app 唔會查歌詞」。
 * 抽出嚟之後手機畫面自己都行得，唔使插車先睇到歌詞 —— 亦即係大部分功能
 * 可以喺手上一部機度驗證，唔使次次都要一部連住車嘅機。
 *
 * 刷新策略：250ms tick 推算位置，但**只有當前行號變咗**先出新 state。
 * 快歌一句 2 秒 → 每 2 秒先更新一次。
 */
class LyricsFeed(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(LyricsFeedState())
    val state: StateFlow<LyricsFeedState> = mutable

    private var lookupJob: Job? = null

    fun start() {
        scope.launch {
            ActiveMediaWatcher.state.collectLatest { playback ->
                val cur = mutable.value
                val changed = playback?.title != cur.nowPlaying?.title ||
                    playback?.artist != cur.nowPlaying?.artist

                if (changed) {
                    mutable.value = LyricsFeedState(
                        nowPlaying = playback,
                        status = if (playback != null) LyricsStatus.LOADING else LyricsStatus.IDLE,
                    )
                    if (playback != null) startLookup(playback)
                } else {
                    mutable.value = cur.copy(nowPlaying = playback)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val s = mutable.value
                val playback = s.nowPlaying ?: continue
                val parsed = s.lyrics ?: continue
                val position = PositionEstimator.estimate(playback, SystemClock.elapsedRealtime())
                val line = LyricsSync.currentLineIndex(parsed, position)
                if (line != s.currentLine) {
                    mutable.value = s.copy(currentLine = line)
                }
            }
        }
    }

    private fun startLookup(playback: PlaybackState) {
        lookupJob?.cancel()
        lookupJob = scope.launch {
            val result = AppGraph.repository(context)
                .lookup(TrackKey(playback.title, playback.artist))
            // 查完之後首歌可能已經換咗 —— 要同時比對歌手，因為唔同歌手可以有同名歌
            val cur = mutable.value
            if (playback.title != cur.nowPlaying?.title ||
                playback.artist != cur.nowPlaying?.artist
            ) {
                return@launch
            }
            // 空歌詞當搵唔到 —— 咁 FOUND 就恆常代表「真係有嘢畫得出」，
            // 顯示嗰邊唔使再自己分多次。
            val found = (result as? LyricsResult.Found)?.lyrics?.takeIf { !it.isEmpty }
            mutable.value = cur.copy(
                lyrics = found,
                currentLine = null,
                status = when {
                    found != null -> LyricsStatus.FOUND
                    result is LyricsResult.Error -> LyricsStatus.ERROR
                    else -> LyricsStatus.NOT_FOUND
                },
            )
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
