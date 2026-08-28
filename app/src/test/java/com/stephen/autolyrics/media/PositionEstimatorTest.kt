package com.stephen.autolyrics.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionEstimatorTest {

    private fun state(
        positionMs: Long = 10_000,
        updateTime: Long = 1_000,
        speed: Float = 1.0f,
        playing: Boolean = true,
    ) = PlaybackState(
        title = "t", artist = "a", album = null,
        positionMs = positionMs,
        positionUpdateTimeMs = updateTime,
        playbackSpeed = speed,
        isPlaying = playing,
    )

    @Test
    fun `advances position by elapsed time while playing`() {
        // 報告時 position=10s；之後過咗 2s → 12s
        assertEquals(12_000L, PositionEstimator.estimate(state(), nowMs = 3_000))
    }

    @Test
    fun `does not advance while paused`() {
        val paused = state(playing = false)
        assertEquals(10_000L, PositionEstimator.estimate(paused, nowMs = 99_000))
    }

    @Test
    fun `respects playback speed`() {
        // 1.5x：過咗 2s 實際行咗 3s
        assertEquals(13_000L, PositionEstimator.estimate(state(speed = 1.5f), nowMs = 3_000))
    }

    @Test
    fun `uses the new baseline after a seek`() {
        // seek 到 60s，報告時間 5_000
        val seeked = state(positionMs = 60_000, updateTime = 5_000)
        assertEquals(61_000L, PositionEstimator.estimate(seeked, nowMs = 6_000))
    }

    @Test
    fun `returns the reported position when no time has elapsed`() {
        assertEquals(10_000L, PositionEstimator.estimate(state(), nowMs = 1_000))
    }

    @Test
    fun `clamps negative elapsed time to the reported position`() {
        // now 早過 update time（時鐘跳動）→ 唔應該倒退
        assertEquals(10_000L, PositionEstimator.estimate(state(), nowMs = 500))
    }

    @Test
    fun `treats zero speed as paused`() {
        assertEquals(10_000L, PositionEstimator.estimate(state(speed = 0f), nowMs = 99_000))
    }
}
