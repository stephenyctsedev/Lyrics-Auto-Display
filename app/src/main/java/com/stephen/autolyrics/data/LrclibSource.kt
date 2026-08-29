package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.LrcParser
import com.stephen.autolyrics.lyrics.TrackKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * LRCLIB 查詢。只做 GET，只送歌名同歌手 —— 冇裝置 ID、冇帳號、冇位置。
 *
 * 唔做 cert pinning：LRCLIB 用 Let's Encrypt，證書會轉，pin 咗會令 app 突然壞。
 * 靠系統 CA + manifest 嘅 usesCleartextTraffic=false。
 */
class LrclibSource(
    private val baseUrl: String = "https://lrclib.net",
    private val client: OkHttpClient,
    private val userAgent: String,
) : LyricsSource {

    override suspend fun lookup(key: TrackKey): LyricsResult = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", key.artist)
            .addQueryParameter("track_name", key.title)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> LyricsResult.NotFound
                    !response.isSuccessful ->
                        LyricsResult.Error("HTTP ${response.code}")
                    else -> parseBody(response.body?.string().orEmpty())
                }
            }
        } catch (e: Exception) {
            LyricsResult.Error(e.javaClass.simpleName + ": " + (e.message ?: "network failure"))
        }
    }

    private fun parseBody(body: String): LyricsResult = try {
        val json = JSONObject(body)
        val synced = if (json.isNull("syncedLyrics")) null else json.getString("syncedLyrics")
        val instrumental = json.optBoolean("instrumental", false)

        when {
            instrumental || synced.isNullOrBlank() -> LyricsResult.NotFound
            else -> {
                val parsed = LrcParser.parse(synced)
                // 冇時間戳 = 跟唔到秒數，當搵唔到處理
                if (parsed.isEmpty) LyricsResult.NotFound
                else LyricsResult.Found(parsed, LyricsOrigin.NETWORK)
            }
        }
    } catch (e: Exception) {
        LyricsResult.Error("malformed response")
    }
}
