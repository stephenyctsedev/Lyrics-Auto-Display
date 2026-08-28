package com.stephen.autolyrics.lyrics

/**
 * 解析 LRC 格式歌詞。支援 [mm:ss.xx] 同 [mm:ss.xxx]，
 * 同一行多個時間戳會展開成多筆，metadata 標籤（[ar:] 等）略過。
 */
object LrcParser {

    // 只夾 [數字:數字.數字]，metadata 如 [ar:X] 唔會 match
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{2,3}))?]""")

    fun parse(raw: String): ParsedLyrics {
        if (raw.isBlank()) return ParsedLyrics(emptyList())

        val out = mutableListOf<LyricLine>()

        raw.removePrefix("﻿").split('\n').forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            // 文字 = 最後一個時間戳之後嘅嘢
            val text = line.substring(matches.last().range.last + 1).trim()

            matches.forEach { m ->
                val minutes = m.groupValues[1].toLong()
                val seconds = m.groupValues[2].toLong()
                val fraction = m.groupValues[3]
                val fractionMs = when (fraction.length) {
                    3 -> fraction.toLong()
                    2 -> fraction.toLong() * 10
                    else -> 0L
                }
                out += LyricLine(minutes * 60_000 + seconds * 1_000 + fractionMs, text)
            }
        }

        return ParsedLyrics(out.sortedBy { it.timeMs })
    }
}
