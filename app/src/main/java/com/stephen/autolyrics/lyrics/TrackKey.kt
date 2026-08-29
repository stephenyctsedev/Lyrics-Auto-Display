package com.stephen.autolyrics.lyrics

data class TrackKey(val title: String, val artist: String) {

    /**
     * 輕度正規化：去掉常見嘅版本後綴、統一大細階同空白。
     * 只去「已知雜訊」，唔會亂咁刪括號 —— 例如 "(Reprise)" 係歌名一部分要留低。
     */
    fun normalized(): TrackKey =
        TrackKey(normalizeTitle(title), collapse(artist))

    /** Room 主鍵：正規化後嘅 artist|title，separator 已 escape 避免碰撞。 */
    fun cacheKey(): String = normalized().let { "${escape(it.artist)}|${escape(it.title)}" }

    private companion object {
        val NOISE = listOf(
            "remaster", "remastered", "live", "feat.", "feat ", "ft.",
            "radio edit", "single version", "album version", "explicit",
            "bonus track", "deluxe", "mono", "stereo",
        )

        // 將每個 NOISE 字眼變做一個「字界」regex：
        // - 去尾嘅標點/空白（例如 "feat.", "feat ", "ft."）唔算入字界比較，
        //   淨係要求個字眼前面同（去咗標點之後嘅）後面唔係字母數字。
        // - 多個字（"radio edit" 等）當做一個詞組，前後都要係字界。
        val NOISE_PATTERNS: List<Regex> = NOISE.map { word ->
            val core = word.trim().trimEnd('.').trim()
            val escaped = Regex.escape(core)
            Regex("""(?<![\p{L}\p{N}])$escaped(?![\p{L}\p{N}])""")
        }

        fun containsNoise(text: String): Boolean = NOISE_PATTERNS.any { it.containsMatchIn(text) }

        // Escape separator so distinct (artist, title) pairs can't collide in cacheKey.
        // '|' -> "\|", and existing backslashes are escaped first so the encoding is unambiguous.
        fun escape(s: String): String = s.replace("\\", "\\\\").replace("|", "\\|")

        fun collapse(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

        fun normalizeTitle(raw: String): String {
            var s = collapse(raw)

            // 去掉內容含已知雜訊字眼嘅 (...) / [...] 區段
            s = Regex("""[(\[]([^)\]]*)[)\]]""").replace(s) { m ->
                val inner = m.groupValues[1]
                if (containsNoise(inner)) "" else m.value
            }

            // 去掉 " - <雜訊>" 尾巴
            val dash = s.lastIndexOf(" - ")
            if (dash > 0) {
                val tail = s.substring(dash + 3)
                if (containsNoise(tail)) s = s.substring(0, dash)
            }

            val cleaned = collapse(s)
            // 全部被刪走就退返原本，避免產生空 query
            return cleaned.ifBlank { collapse(raw) }
        }
    }
}
