package com.stephen.autolyrics.lyrics

data class LyricLine(val timeMs: Long, val text: String)

data class ParsedLyrics(val lines: List<LyricLine>) {
    val isEmpty: Boolean get() = lines.isEmpty()
}
