package com.stephen.autolyrics.auto

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Android Auto 用 MediaBrowserService 呢條路，唔用 CarAppService + template。
 *
 * 點解：Android Auto 淨係接受固定幾個 category 嘅 template app，「歌詞顯示」
 * 唔屬於任何一個 —— POI + template 試過，個 app 根本唔會喺 launcher 出現。
 * Media 就係官方支援嘅類別，所以行呢條路。
 *
 * 個 trick 係：將歌詞當「可瀏覽嘅曲目清單」餵畀 host —— onLoadChildren() 回
 * 當前歌詞嘅頭尾幾行做 MediaItem，再靠 notifyChildrenChanged() 叫 host 重新
 * 拉一次，睇落就變咗滾動嘅歌詞。
 *
 * 呢個 class 而家係骨架：淨係回一個 hardcode item，用嚟驗證「個 app 到底
 * 喺唔喺 Customize launcher 出現」。接真資料係下一步。
 */
class LyricsBrowserService : MediaBrowserServiceCompat() {

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // 呢個 service 喺 manifest 係 exported=true（Android Auto 要 bind 到），
        // 所以機上任何一個 app 都 bind 得到。冇呢個檢查嘅話，第三方 app 可以
        // 讀到你播緊咩同埋當前歌詞。
        //
        // 回 null = 拒絕連接。
        if (!CallerValidator.isAllowed(this, clientPackageName, clientUid)) return null
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        // Step 1 骨架：固定文字，證明 host 連得到、個 list 出到嚟。
        items.add(
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("placeholder")
                    .setTitle("Auto Lyrics")
                    .setSubtitle("連接成功 —— 歌詞功能未接")
                    .build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        )

        result.sendResult(items)
    }

    companion object {
        private const val ROOT_ID = "root"
    }
}
