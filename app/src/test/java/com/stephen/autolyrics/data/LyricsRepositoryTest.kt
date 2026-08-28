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
        val source = object : LyricsSource {
            var seen: TrackKey? = null
            override suspend fun lookup(key: TrackKey): LyricsResult {
                seen = key
                return LyricsResult.NotFound
            }
        }
        val r = LyricsRepository(FakeDao(), source, QueryLog(), nowMs = { 100_000 })

        r.lookup(TrackKey("Song Name (Remastered 2011)", "Artist"))

        assertEquals("song name", source.seen?.title)
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
}
