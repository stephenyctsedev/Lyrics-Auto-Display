package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.LrcParser
import com.stephen.autolyrics.lyrics.TrackKey

/**
 * 查詢順序：memory → Room → network。
 *
 * Cache 規則：
 *  - Found    → 永久存 DB（歌詞唔會變）
 *  - NotFound → 存 negative cache，TTL 7 日
 *  - Error    → **唔存**（503 / timeout 唔可以封住首歌）
 */
class LyricsRepository(
    private val dao: LyricsDao,
    private val networkSource: LyricsSource,
    private val log: QueryLog,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val memory = mutableMapOf<String, LyricsResult>()

    suspend fun lookup(key: TrackKey): LyricsResult {
        val normalized = key.normalized()
        val cacheKey = key.cacheKey()

        val result = resolve(normalized, cacheKey)

        log.record(
            QueryLogEntry(
                title = key.title,
                artist = key.artist,
                queryUsed = normalized.title,
                outcome = when (result) {
                    is LyricsResult.Found -> "Found"
                    is LyricsResult.NotFound -> "NotFound"
                    is LyricsResult.Error -> "Error: ${result.reason}"
                },
                origin = (result as? LyricsResult.Found)?.origin,
                atMs = nowMs(),
            )
        )
        return result
    }

    private suspend fun resolve(normalized: TrackKey, cacheKey: String): LyricsResult {
        // 1. Memory
        memory[cacheKey]?.let { cached ->
            return when (cached) {
                is LyricsResult.Found -> cached.copy(origin = LyricsOrigin.MEMORY)
                else -> cached
            }
        }

        // 2. Room
        dao.get(cacheKey)?.let { row ->
            val lyrics = row.syncedLyrics
            if (lyrics != null) {
                val hit = LyricsResult.Found(LrcParser.parse(lyrics), LyricsOrigin.DATABASE)
                memory[cacheKey] = hit
                return hit
            }
            // negative entry —— 睇下過咗期未
            if (nowMs() - row.fetchedAtMs < NEGATIVE_TTL_MS) {
                memory[cacheKey] = LyricsResult.NotFound
                return LyricsResult.NotFound
            }
        }

        // 3. Network
        return when (val fresh = networkSource.lookup(normalized)) {
            is LyricsResult.Found -> {
                dao.put(LyricsEntity(cacheKey, rawOf(fresh), nowMs()))
                memory[cacheKey] = fresh
                fresh
            }
            is LyricsResult.NotFound -> {
                dao.put(LyricsEntity(cacheKey, null, nowMs()))
                memory[cacheKey] = LyricsResult.NotFound
                LyricsResult.NotFound
            }
            // 暫時性失敗：唔寫 DB、唔寫 memory，下次自然重試
            is LyricsResult.Error -> fresh
        }
    }

    /** 由已解析嘅歌詞砌返 LRC 文字存落 DB。 */
    private fun rawOf(found: LyricsResult.Found): String =
        found.lyrics.lines.joinToString("\n") { line ->
            val totalCs = line.timeMs / 10
            val minutes = totalCs / 6_000
            val seconds = (totalCs / 100) % 60
            val centis = totalCs % 100
            "[%02d:%02d.%02d]%s".format(minutes, seconds, centis, line.text)
        }

    private companion object {
        const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 日
    }
}
