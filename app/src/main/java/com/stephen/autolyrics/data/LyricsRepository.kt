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

        val result = resolve(normalized, key, cacheKey)

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

    private suspend fun resolve(
        normalized: TrackKey,
        original: TrackKey,
        cacheKey: String,
    ): LyricsResult {
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

        // 3. Network —— 正規化後嘅名查唔到，就用原名再試一次。
        // 正規化係 heuristic：真名本身含「live」「mono」呢類字眼嘅歌會被切錯，
        // 冇呢個 fallback 就會白白 negative cache 七日。只有明確 NotFound 先重試，
        // Error（503／timeout）唔重試 —— 交返畀外層唔寫 cache，下次自然再嚟。
        // 用 collapse（淨係統一大細階／空白）做基準去比，先知道正規化係咪真係「切走咗嘢」。
        // 直接同原名比會錯：normalized 一定係細階，所以 "Song Name" 同 "song name"
        // raw string 唔同，但其實乜都冇切走過，重試純屬浪費。
        val collapsedOriginal = original.title.trim().replace(WHITESPACE, " ").lowercase()
        val strippedSomething = collapsedOriginal != normalized.title

        val firstTry = networkSource.lookup(normalized)
        val fresh = if (firstTry is LyricsResult.NotFound && strippedSomething) {
            // 用返「只 collapse、冇切後綴」嘅名重試，唔係用未處理嘅 raw title。
            networkSource.lookup(TrackKey(collapsedOriginal, normalized.artist))
        } else {
            firstTry
        }

        return when (fresh) {
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
        val WHITESPACE = Regex("\\s+")
    }
}
