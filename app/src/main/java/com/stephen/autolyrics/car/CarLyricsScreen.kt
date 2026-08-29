package com.stephen.autolyrics.car

import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.CarText
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.stephen.autolyrics.AppGraph
import com.stephen.autolyrics.data.LyricsResult
import com.stephen.autolyrics.lyrics.LyricsSync
import com.stephen.autolyrics.lyrics.ParsedLyrics
import com.stephen.autolyrics.lyrics.TrackKey
import com.stephen.autolyrics.media.ActiveMediaWatcher
import com.stephen.autolyrics.media.PlaybackState
import com.stephen.autolyrics.media.PositionEstimator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 單一 screen，原地刷新。
 *
 * Refresh 策略：250ms tick 推算位置，但**只有當前行號變咗**先 invalidate()。
 * Android Auto host 會合併短時間內嘅多次更新，所以減少 invalidate 次數
 * 直接改善顯示流暢度。快歌一句 2 秒 → 每 2 秒先 invalidate 一次。
 */
class CarLyricsScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var nowPlaying: PlaybackState? = null
    private var lyrics: ParsedLyrics? = null
    private var currentLine: Int? = null
    private var lookupJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        // 訂閱播放狀態：換歌就重新查歌詞
        lifecycleScope.launch {
            ActiveMediaWatcher.state.collectLatest { state ->
                val changed = state?.title != nowPlaying?.title ||
                    state?.artist != nowPlaying?.artist
                nowPlaying = state

                if (changed) {
                    lyrics = null
                    currentLine = null
                    invalidate()
                    if (state != null) startLookup(state)
                }
            }
        }

        // 250ms ticker：推算位置，行號變咗先重畫
        lifecycleScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val state = nowPlaying ?: continue
                val parsed = lyrics ?: continue
                val position = PositionEstimator.estimate(state, SystemClock.elapsedRealtime())
                val line = LyricsSync.currentLineIndex(parsed, position)
                if (line != currentLine) {
                    currentLine = line
                    invalidate()
                }
            }
        }
    }

    private fun startLookup(state: PlaybackState) {
        lookupJob?.cancel()
        lookupJob = lifecycleScope.launch {
            val result = AppGraph.repository(carContext)
                .lookup(TrackKey(state.title, state.artist))
            // 查完之後首歌可能已經換咗 —— 要同時比對歌手，因為唔同歌手可以有同名歌
            if (state.title != nowPlaying?.title || state.artist != nowPlaying?.artist) return@launch
            lyrics = (result as? LyricsResult.Found)?.lyrics
            currentLine = null
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        val state = nowPlaying
        val parsed = lyrics

        if (state == null) {
            pane.addRow(Row.Builder().setTitle("冇偵測到播放中嘅音樂").build())
        } else if (parsed == null || parsed.isEmpty) {
            // 靜靜降級：搵唔到 / 未查完 / 網絡唔通，一律顯示歌名歌手，唔出 error
            pane.addRow(
                Row.Builder()
                    .setTitle(CarText.create(state.title))
                    .addText(CarText.create(state.artist))
                    .build()
            )
        } else {
            visibleWindow(parsed).forEach { (index, text) ->
                val display = if (text.isBlank()) " " else text
                pane.addRow(
                    Row.Builder()
                        .setTitle(CarText.create(
                            if (index == currentLine) "▶ $display" else display
                        ))
                        .build()
                )
            }
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle(nowPlaying?.title ?: "Auto Lyrics")
            .build()
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

    private companion object {
        const val TICK_MS = 250L
        const val WINDOW_SIZE = 4  // PaneTemplate 有行數上限，保守取 4
    }
}
