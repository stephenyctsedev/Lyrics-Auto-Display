package com.stephen.autolyrics.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LyricsEntity::class], version = 1, exportSchema = false)
abstract class LyricsDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsDao

    companion object {
        @Volatile private var instance: LyricsDatabase? = null

        fun get(context: Context): LyricsDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LyricsDatabase::class.java,
                "lyrics.db",
            ).build().also { instance = it }
        }
    }
}
