package com.stephen.autolyrics.data

data class QueryLogEntry(
    val title: String,
    val artist: String,
    val queryUsed: String,
    val outcome: String,
    val origin: LyricsOrigin?,
    val atMs: Long,
)

/**
 * Debug 用嘅 in-memory ring buffer —— 最新嘅排頭。
 * 淨係喺手機 UI 顯示，唔會寫落 disk、唔會上傳。
 */
class QueryLog(private val capacity: Int = 50) {

    private val buffer = ArrayDeque<QueryLogEntry>()

    val entries: List<QueryLogEntry>
        @Synchronized get() = buffer.toList()

    @Synchronized
    fun record(entry: QueryLogEntry) {
        buffer.addFirst(entry)
        while (buffer.size > capacity) buffer.removeLast()
    }
}
