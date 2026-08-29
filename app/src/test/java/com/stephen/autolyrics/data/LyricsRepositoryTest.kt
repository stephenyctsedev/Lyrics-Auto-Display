package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.TrackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    private val key = TrackKey("Song", "Artist")

    /** 記住 lookup 次數嘅假 network source。 */
    private class FakeSource(var result: LyricsResult) : LyricsSource {
        var calls = 0
        override suspend fun lookup(key: TrackKey): LyricsResult {
            calls++
            return result
        }
    }

    /** In-memory 假 DAO。 */
    private class FakeDao : LyricsDao {
        val rows = mutableMapOf<String, LyricsEntity>()
        var getCalls = 0
        override suspend fun get(cacheKey: String): LyricsEntity? {
            getCalls++
            return rows[cacheKey]
        }
        override suspend fun put(entity: LyricsEntity) { rows[entity.cacheKey] = entity }
        override suspend fun deleteExpiredNegatives(cutoffMs: Long) {
            rows.entries.removeAll { it.value.syncedLyrics == null && it.value.fetchedAtMs < cutoffMs }
        }
    }

    private fun repo(
        dao: FakeDao = FakeDao(),
        source: FakeSource = FakeSource(LyricsResult.NotFound),
        now: Long = 100_000,
    ) = LyricsRepository(dao, source, QueryLog(), nowMs = { now })

    private val foundResult = LyricsResult.Found(
        com.stephen.autolyrics.lyrics.LrcParser.parse("[00:01.00]line one"),
        LyricsOrigin.NETWORK,
    )

    @Test
    fun `fetches from network on a cold cache and stores the result`() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        val result = r.lookup(key)

        assertTrue(result is LyricsResult.Found)
        assertEquals(1, source.calls)
        assertEquals("[00:01.00]line one", dao.rows[key.cacheKey()]?.syncedLyrics)
    }

    @Test
    fun `memory hit does not touch the dao`() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        r.lookup(key)
        val daoCallsAfterFirst = dao.getCalls
        val result = r.lookup(key)

        assertTrue(result is LyricsResult.Found)
        assertEquals(LyricsOrigin.MEMORY, (result as LyricsResult.Found).origin)
        assertEquals(daoCallsAfterFirst, dao.getCalls)  // 冇再問 DB
        assertEquals(1, source.calls)                   // 冇再出網絡
    }

    @Test
    fun `database hit does not hit the network`() = runBlocking {
        val dao = FakeDao().apply {
            rows[key.cacheKey()] = LyricsEntity(key.cacheKey(), "[00:01.00]line one", 90_000)
        }
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        val result = r.lookup(key)

        assertEquals(LyricsOrigin.DATABASE, (result as LyricsResult.Found).origin)
        assertEquals(0, source.calls)
    }

    @Test
    fun `stores a negative cache entry on NotFound`() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(LyricsResult.NotFound)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        r.lookup(key)

        assertTrue(dao.rows.containsKey(key.cacheKey()))
        assertEquals(null, dao.rows[key.cacheKey()]?.syncedLyrics)
    }

    @Test
    fun `unexpired negative cache is not re-queried`() = runBlocking {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        val dao = FakeDao().apply {
            rows[key.cacheKey()] = LyricsEntity(key.cacheKey(), null, fetchedAtMs = 1_000)
        }
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 1_000 + sevenDays - 1 })

        assertEquals(LyricsResult.NotFound, r.lookup(key))
        assertEquals(0, source.calls)
    }

    @Test
    fun `expired negative cache is re-queried`() = runBlocking {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        val dao = FakeDao().apply {
            rows[key.cacheKey()] = LyricsEntity(key.cacheKey(), null, fetchedAtMs = 1_000)
        }
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 1_000 + sevenDays + 1 })

        assertTrue(r.lookup(key) is LyricsResult.Found)
        assertEquals(1, source.calls)
    }

    @Test
    fun `network Error does not write a negative cache entry`() = runBlocking {
        // 關鍵：503 / timeout 唔可以封住首歌 7 日
        val dao = FakeDao()
        val source = FakeSource(LyricsResult.Error("HTTP 503"))
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        val result = r.lookup(key)

        assertTrue(result is LyricsResult.Error)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `network Error is not cached in memory either`() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(LyricsResult.Error("HTTP 503"))
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        r.lookup(key)
        r.lookup(key)

        assertEquals(2, source.calls)  // 兩次都要重試
    }

    @Test
    fun `queries with the normalized title`() = runBlocking {
        // 記低每一次 query：搵唔到嗰陣會有第二次（原名 fallback），
        // 但呢個 test 關心嘅係「第一次一定要用正規化後嘅名」。
        val source = RecordingSource(mutableListOf(LyricsResult.NotFound))
        val r = LyricsRepository(FakeDao(), source, QueryLog(), nowMs = { 100_000 })

        r.lookup(TrackKey("Song Name (Remastered 2011)", "Artist"))

        assertEquals("song name", source.seen.first().title)
    }

    @Test
    fun `records each lookup in the query log`() = runBlocking {
        val log = QueryLog()
        val r = LyricsRepository(FakeDao(), FakeSource(LyricsResult.NotFound), log, nowMs = { 100_000 })

        r.lookup(TrackKey("Song Name (Live)", "Artist"))

        assertEquals(1, log.entries.size)
        assertEquals("Song Name (Live)", log.entries[0].title)
        assertEquals("song name", log.entries[0].queryUsed)
        assertEquals("NotFound", log.entries[0].outcome)
    }

    @Test
    fun `query log keeps only the most recent entries`() = runBlocking {
        val log = QueryLog(capacity = 3)
        val r = LyricsRepository(FakeDao(), FakeSource(LyricsResult.NotFound), log, nowMs = { 100_000 })

        repeat(5) { i -> r.lookup(TrackKey("Song $i", "Artist")) }

        assertEquals(3, log.entries.size)
        assertEquals("Song 4", log.entries.first().title)  // 最新排頭
    }

    /** 記低每次收到嘅 query，用嚟驗證 fallback 真係用返原名再試。 */
    private class RecordingSource(
        private val results: MutableList<LyricsResult>,
    ) : LyricsSource {
        val seen = mutableListOf<TrackKey>()
        override suspend fun lookup(key: TrackKey): LyricsResult {
            seen += key
            return if (results.isEmpty()) LyricsResult.NotFound else results.removeAt(0)
        }
    }

    @Test
    fun `retries with the original title when the normalized query misses`() = runBlocking {
        // 「(Live)」係真後綴嘅寫法，正規化一定會切走。假設呢首歌喺 LRCLIB 入面
        // 就係連後綴一齊收錄，切走之後反而搵唔到 —— 呢個時候要用全名再試一次。
        val source = RecordingSource(
            mutableListOf(LyricsResult.NotFound, foundResult)
        )
        val dao = FakeDao()
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        val result = r.lookup(TrackKey("Song Name (Live)", "Artist"))

        assertTrue(result is LyricsResult.Found)
        assertEquals(2, source.seen.size)
        assertEquals("song name", source.seen[0].title)         // 正規化後（切走咗後綴）
        assertEquals("song name (live)", source.seen[1].title)  // fallback：冇切後綴嘅全名
    }

    @Test
    fun `does not retry when the normalized title is unchanged`() = runBlocking {
        val source = RecordingSource(mutableListOf(LyricsResult.NotFound))
        val r = LyricsRepository(FakeDao(), source, QueryLog(), nowMs = { 100_000 })

        r.lookup(TrackKey("Song Name", "Artist"))

        // 正規化冇改到個名，再查一次係浪費
        assertEquals(1, source.seen.size)
    }

    @Test
    fun `does not retry the original title on a transient error`() = runBlocking {
        // 503 / timeout 唔係「搵唔到」，重試第二個 query 冇意義
        val source = RecordingSource(mutableListOf(LyricsResult.Error("HTTP 503")))
        val dao = FakeDao()
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        val result = r.lookup(TrackKey("Song Name (Remastered 2011)", "Artist"))

        assertTrue(result is LyricsResult.Error)
        assertEquals(1, source.seen.size)
        assertTrue(dao.rows.isEmpty())  // 仍然唔可以寫 negative cache
    }
}
