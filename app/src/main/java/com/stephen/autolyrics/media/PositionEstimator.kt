package com.stephen.autolyrics.media

/**
 * 本地推算當前播放位置，避免每個 tick 都去問系統。
 * 估算位置 = positionMs + (now - positionUpdateTimeMs) × playbackSpeed
 */
object PositionEstimator {

    fun estimate(state: PlaybackState, nowMs: Long): Long {
        if (!state.isPlaying || state.playbackSpeed <= 0f) return state.positionMs
        val elapsed = nowMs - state.positionUpdateTimeMs
        if (elapsed <= 0) return state.positionMs
        return state.positionMs + (elapsed * state.playbackSpeed).toLong()
    }
}
