package com.stephen.autolyrics.media

/**
 * 播放狀態快照。
 *
 * positionUpdateTimeMs 係「報告 positionMs 嗰一刻」嘅 SystemClock.elapsedRealtime()，
 * 唔係 wall clock —— 推算位置直接靠佢做基準點。
 */
data class PlaybackState(
    val title: String,
    val artist: String,
    val album: String?,
    val positionMs: Long,
    val positionUpdateTimeMs: Long,
    val playbackSpeed: Float,
    val isPlaying: Boolean,
)
