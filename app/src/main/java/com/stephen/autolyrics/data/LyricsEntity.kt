package com.stephen.autolyrics.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * cacheKey = TrackKey.cacheKey()（正規化後嘅 "artist|title"）。
 * syncedLyrics == null 代表 negative cache：LRCLIB 明確答冇。
 */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val cacheKey: String,
    val syncedLyrics: String?,
    val fetchedAtMs: Long,
)

@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: LyricsEntity)

    /** 只清過期嘅負面結果；正面結果永久保留（歌詞唔會變）。 */
    @Query("DELETE FROM lyrics WHERE syncedLyrics IS NULL AND fetchedAtMs < :cutoffMs")
    suspend fun deleteExpiredNegatives(cutoffMs: Long)
}
