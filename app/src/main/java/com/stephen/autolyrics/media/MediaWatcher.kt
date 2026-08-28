package com.stephen.autolyrics.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface MediaWatcher {
    val state: StateFlow<PlaybackState?>
}

/**
 * 畀兩個 UI 訂閱嘅單例入口。NotificationMediaWatcher 係唯一嘅寫入者。
 */
object ActiveMediaWatcher : MediaWatcher {
    internal val mutableState = MutableStateFlow<PlaybackState?>(null)
    override val state: StateFlow<PlaybackState?> = mutableState
}
