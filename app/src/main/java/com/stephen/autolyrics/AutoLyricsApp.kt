package com.stephen.autolyrics

import android.content.Context
import com.stephen.autolyrics.data.LrclibSource
import com.stephen.autolyrics.data.LyricsDatabase
import com.stephen.autolyrics.data.LyricsRepository
import com.stephen.autolyrics.data.QueryLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 手動 DI —— 車機同手機兩邊共用同一個 repository / log 實例。
 */
object AppGraph {

    val queryLog = QueryLog()

    // UA 寫真實資料：app 名 + 版本 + 本 repo。LRCLIB 要求 UA 可識別。
    private const val USER_AGENT =
        "AutoLyrics/${BuildConfig.VERSION_NAME} (https://github.com/stephenyctsedev/Lyrics-Auto-Display)"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var repo: LyricsRepository? = null

    fun repository(context: Context): LyricsRepository = repo ?: synchronized(this) {
        repo ?: LyricsRepository(
            dao = LyricsDatabase.get(context).lyricsDao(),
            networkSource = LrclibSource(client = httpClient, userAgent = USER_AGENT),
            log = queryLog,
        ).also { repo = it }
    }
}
