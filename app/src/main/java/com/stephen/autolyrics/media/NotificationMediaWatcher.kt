package com.stephen.autolyrics.media

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 唯一掂 NotificationListener 嘅檔案。
 *
 * ─── 三個自我約束（README 有講，呢度係實際執行嘅地方）───────────────
 *
 * 1. onNotificationPosted / onNotificationRemoved **只**用嚟觸發 session 重新掃描。
 *    callback 入面唔讀 StatusBarNotification 嘅任何內容欄位 ——
 *    唔碰 sbn.notification.extras、tickerText、actions。參數名寫成 `unused` 就係為咗
 *    喺 code 層面講清楚：我哋唔睇通知內容。
 *
 * 2. 唔用 MediaController 嘅任何控制 API（play() / pause() / skipToNext() 等）。
 *    只讀 metadata 同 playbackState。
 *
 * 3. 唔記錄、唔上傳任何通知相關資料。
 *
 * ────────────────────────────────────────────────────────────
 */
class NotificationMediaWatcher : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindTo(controllers?.firstOrNull())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: AndroidPlaybackState?) = publish()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, NotificationMediaWatcher::class.java)
        sessionManager =
            (getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager).also { manager ->
                manager.addOnActiveSessionsChangedListener(sessionsListener, component)
                bindTo(manager.getActiveSessions(component).firstOrNull())
            }
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(controllerCallback)
        controller = null
        ActiveMediaWatcher.mutableState.value = null
        super.onListenerDisconnected()
    }

    // 約束 1：只 trigger 重掃，唔讀通知內容
    override fun onNotificationPosted(unused: StatusBarNotification?) = refreshSessions()
    override fun onNotificationRemoved(unused: StatusBarNotification?) = refreshSessions()

    private fun refreshSessions() {
        val manager = sessionManager ?: return
        val component = ComponentName(this, NotificationMediaWatcher::class.java)
        bindTo(manager.getActiveSessions(component).firstOrNull())
    }

    private fun bindTo(next: MediaController?) {
        if (next?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback)
        publish()
    }

    /** 約束 2：只讀 metadata 同 playbackState，唔掂任何控制 API。 */
    private fun publish() {
        val active = controller
        val metadata = active?.metadata
        val playback = active?.playbackState

        if (metadata == null || playback == null) {
            ActiveMediaWatcher.mutableState.value = null
            return
        }

        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()

        if (title.isBlank() || artist.isBlank()) {
            ActiveMediaWatcher.mutableState.value = null
            return
        }

        ActiveMediaWatcher.mutableState.value = PlaybackState(
            title = title,
            artist = artist,
            album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            positionMs = playback.position,
            // getLastPositionUpdateTime() 係 elapsedRealtime 基準，同 PositionEstimator 對得上
            positionUpdateTimeMs = playback.lastPositionUpdateTime
                .takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            playbackSpeed = playback.playbackSpeed,
            isPlaying = playback.state == AndroidPlaybackState.STATE_PLAYING,
        )
    }
}
