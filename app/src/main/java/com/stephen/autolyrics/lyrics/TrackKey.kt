package com.stephen.autolyrics.lyrics

data class TrackKey(val title: String, val artist: String) {

    /**
     * 輕度正規化：去掉常見嘅版本後綴、統一大細階同空白。
     * 只去「已知雜訊」，唔會亂咁刪括號 —— 例如 "(Reprise)" 係歌名一部分要留低。
     */
    fun normalized(): TrackKey =
        TrackKey(normalizeTitle(title), collapse(artist))

    /** Room 主鍵：正規化後嘅 artist|title。 */
    fun cacheKey(): String = normalized().let { "${it.artist}|${it.title}" }

    private companion object {
        val NOISE = listOf(
            "remaster", "remastered", "live", "feat.", "feat ", "ft.",
            "radio edit", "single version", "album version", "explicit",
            "bonus track", "deluxe", "mono", "stereo",
        )

        fun collapse(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

        fun normalizeTitle(raw: String): String {
            var s = collapse(raw)

            // 去掉內容含已知雜訊字眼嘅 (...) / [...] 區段
            s = Regex("""[(\[]([^)\]]*)[)\]]""").replace(s) { m ->
                val inner = m.groupValues[1]
                if (NOISE.any { inner.contains(it) }) "" else m.value
            }

            // 去掉 " - <雜訊>" 尾巴
            val dash = s.lastIndexOf(" - ")
            if (dash > 0) {
                val tail = s.substring(dash + 3)
                if (NOISE.any { tail.contains(it) }) s = s.substring(0, dash)
            }

            val cleaned = collapse(s)
            // 全部被刪走就退返原本，避免產生空 query
            return cleaned.ifBlank { collapse(raw) }
        }
    }
}
