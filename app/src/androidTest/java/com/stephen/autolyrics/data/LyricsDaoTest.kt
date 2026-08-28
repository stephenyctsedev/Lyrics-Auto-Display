package com.stephen.autolyrics.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsDaoTest {

    private lateinit var db: LyricsDatabase
    private lateinit var dao: LyricsDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LyricsDatabase::class.java,
        ).build()
        dao = db.lyricsDao()
    }

    @After fun tearDown() = db.close()

    @Test fun storesAndReadsBackLyrics() = runBlocking {
        dao.put(LyricsEntity("artist|song", "[00:01.00]line one", 1_000))
        assertEquals("[00:01.00]line one", dao.get("artist|song")?.syncedLyrics)
    }

    @Test fun returnsNullForMissingKey() = runBlocking {
        assertNull(dao.get("nope|nope"))
    }

    @Test fun storesNegativeEntryAsNullLyrics() = runBlocking {
        dao.put(LyricsEntity("artist|song", null, 1_000))
        val row = dao.get("artist|song")
        assertNull(row?.syncedLyrics)
        assertEquals(1_000L, row?.fetchedAtMs)
    }

    @Test fun putReplacesExistingKey() = runBlocking {
        dao.put(LyricsEntity("artist|song", null, 1_000))
        dao.put(LyricsEntity("artist|song", "[00:01.00]line one", 2_000))
        assertEquals("[00:01.00]line one", dao.get("artist|song")?.syncedLyrics)
    }

    @Test fun deleteExpiredNegativesRemovesOnlyOldNegatives() = runBlocking {
        dao.put(LyricsEntity("a|old-negative", null, 1_000))
        dao.put(LyricsEntity("a|new-negative", null, 9_000))
        dao.put(LyricsEntity("a|old-positive", "[00:01.00]line one", 1_000))

        dao.deleteExpiredNegatives(cutoffMs = 5_000)

        assertNull(dao.get("a|old-negative"))
        assertEquals(9_000L, dao.get("a|new-negative")?.fetchedAtMs)
        // 正面結果永久保留，唔受 cutoff 影響
        assertEquals("[00:01.00]line one", dao.get("a|old-positive")?.syncedLyrics)
    }
}
