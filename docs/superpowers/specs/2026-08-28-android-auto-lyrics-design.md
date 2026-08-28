# Android Auto 歌詞顯示 App — 設計文件

日期：2026-08-28
狀態：設計已確認，未開始實作
參考：`Tech Notes/Android App 安全審查 - auto-lyrics 個案與自建 App Checklist`（Obsidian，Memory vault）

---

## 目標

一個 Android app，喺 Android Auto 車機畫面上顯示當前播放歌曲嘅同步歌詞，跟住播放位置逐句更新。

**非目標：**

- 唔上 Google Play（Android Auto category 白名單冇「歌詞」呢一類，大概率過唔到審核）。自用 sideload。
- 唔做播放控制（play / pause / skip）。純顯示。
- 唔支援本地 `.lrc` 檔案。歌詞全部線上查詢 + 內部 cache。
- 唔做手機版歌詞顯示。手機 UI 只有權限引導同 debug log。

## 設計決定摘要

| # | 決定 | 理由 |
|---|---|---|
| 1 | 歌詞來源：LRCLIB 唯一線上來源 + 內部 cache | 社群營運、免 API key、無需帳號。避免 auto-lyrics 揀私人 side-project 域名做主來源嘅錯誤 |
| 2 | 播放狀態：NotificationListenerService + MediaSessionManager | Android 冇其他途徑畀第三方讀播放位置。識晒所有播放器 |
| 3 | 顯示：Car App Library，當前句 + 前後各一兩句 | Android Auto 唔畀自由繪製，只可以用固定 template |
| 4 | Build：本地 + GitHub Actions CI，keystore 由 secrets 解碼 | 一次過落實 checklist 全部 CI/CD 項目 |
| 5 | 錯誤處理：Auto 上靜靜降級顯示歌名歌手；手機有 debug log 畫面 | 行車時唔應該有閃動嘅 error 文字；但要有地方查點解搵唔到 |

---

## 架構

四個獨立模組，用窄介面連住：

```
┌─────────────────┐     ┌──────────────────────┐
│ MediaWatcher    │────▶│ PlaybackState        │
│ (系統播放狀態)   │     │ 歌名/歌手/位置/播放中 │
└─────────────────┘     └──────────────────────┘
   NotificationListener            │
   + MediaSessionManager           ▼
                          ┌──────────────────┐
                          │ LyricsRepository │
                          │  cache → LRCLIB  │
                          └──────────────────┘
                                   │  ParsedLyrics
                                   ▼
          ┌────────────────────────┴────────────────────┐
          ▼                                             ▼
┌──────────────────┐                        ┌──────────────────┐
│ CarLyricsScreen  │                        │ Phone UI         │
│ (Android Auto)   │                        │ 權限引導 + log    │
└──────────────────┘                        └──────────────────┘
                          ▲
                  LyricsSync (純函數：位置 → 第幾行)
```

### MediaWatcher

**職責：** 唯一掂 NotificationListener 嘅地方。訂閱 `MediaSessionManager.getActiveSessions()`，對外只出 `Flow<PlaybackState>`。

**介面：**

```kotlin
data class PlaybackState(
    val title: String,
    val artist: String,
    val album: String?,
    val positionMs: Long,           // 報告當刻嘅位置
    val positionUpdateTimeMs: Long, // 報告嗰一刻嘅 elapsedRealtime
    val playbackSpeed: Float,
    val isPlaying: Boolean,
)

interface MediaWatcher {
    val state: Flow<PlaybackState?>  // null = 冇嘢播緊
}
```

**三個自我約束（寫入 code comment，README 亦要講）：**

1. `onNotificationPosted` / `onNotificationRemoved` **只**用嚟觸發 session 重新掃描。callback 入面唔讀 `StatusBarNotification` 嘅任何內容欄位（唔碰 `notification.extras`、`tickerText`、`actions`）。
2. 唔用 `MediaController` 嘅任何控制 API（`play()`、`pause()`、`skipToNext()` 等）。只讀 `metadata` 同 `playbackState`。
3. 唔記錄、唔上傳任何通知相關資料。

因為介面咁窄，其他模組完全唔知道 NotificationListener 存在。將來 Android 換咗攞播放狀態嘅方法，只改呢一個 class。

### LyricsRepository

**職責：** 收「歌名 + 歌手」，出 `ParsedLyrics` 或 `NotFound`。

**查詢順序：** memory cache → Room DB cache → LRCLIB。

**介面：**

```kotlin
sealed interface LyricsResult {
    data class Found(val lyrics: ParsedLyrics, val source: Source) : LyricsResult
    data object NotFound : LyricsResult
    data class Error(val reason: String) : LyricsResult
}

interface LyricsSource {
    suspend fun lookup(track: TrackKey): LyricsResult
}
```

Repository 內部持有一個 `List<LyricsSource>`，順住問。將來想加自建歌詞庫（例如 Synology 上），就係多一個 `LyricsSource` 實作，唔使改 repository 邏輯。

**Cache 策略：**

- 正面結果（搵到歌詞）：Room 永久保留。歌詞唔會變。
- 負面結果（LRCLIB 明確答冇）：記低，TTL **7 日**。避免同一首冷門歌每次播都打網絡；7 日後重試，等 LRCLIB 有機會補上。
- 網絡失敗（timeout、冇訊號、5xx）：**唔記** negative cache。下次自然重試。

### LyricsSync

**職責：** 純函數，零 Android 依賴。畀 `ParsedLyrics` + 當前毫秒數，答「而家係第幾行」。

```kotlin
fun currentLineIndex(lyrics: ParsedLyrics, positionMs: Long): Int?
```

因為冇依賴，呢個係最好測嘅部分 — 全部邊界情況用 JVM unit test 蓋。

### 兩個 UI

各自訂閱上游 flow，互不知道對方存在。

---

## 資料流

### 換歌

`MediaSessionManager` 報 metadata 變 → MediaWatcher 出新 `PlaybackState` → LyricsRepository 查 → 出 `ParsedLyrics` 或 `NotFound` → CarLyricsScreen 收到，重設當前行。

查詢係 async，換歌後有一小段時間未有歌詞。呢段時間 Auto 顯示歌名 + 歌手 — 同「搵唔到」嘅畫面**一樣**，令搵到與否嘅過渡都唔會閃。

### 播放途中跟秒數

**唔可以每秒問系統要位置。** `PlaybackState` 畀嘅係「某一刻嘅位置 + 播放速度」，正確做法係本地推算：

```
估算位置 = positionMs + (elapsedRealtime() - positionUpdateTimeMs) × playbackSpeed
```

App 內部行一個 **250ms ticker**，每次推算位置，餵畀 `LyricsSync`。

**行號冇變就咩都唔做。** 呢個係 refresh throttling 嘅第一道防線 — 畫面只喺換句先 `invalidate()`。快歌一句 2 秒都只係每 2 秒更新一次。

暫停、seek、切歌時，`MediaSessionManager` 報新 state，推算基準點跟住更新。

### 歌名正規化

Metadata 成日有 `(Remastered 2011)`、`- Live`、`feat. X` 等後綴，直接查會 miss。做一層輕度正規化（去括號後綴、統一大細階同空白）再查；查唔到就用**原名**再試一次。

呢個係 heuristic，唔會完美。手機 log 畫面會顯示「實際用咗咩 query」，方便日後調整。

### 已知會白行一次網絡嘅情況

Podcast / 有聲書一樣有 metadata，一樣會查，一樣搵唔到。行為正確，只係浪費一次查詢。第一版唔特別處理。

### 純文字歌詞（無時間戳）

LRCLIB 有啲歌只有純文字歌詞。呢種情況冇得跟秒數捲動。**第一版當佢搵唔到**（顯示歌名歌手）— 喺 Auto 上面顯示一大段唔會郁嘅文字係反效果，同「揀當前句滾動而唔係成段顯示」嘅理由一致。

---

## 錯誤處理

**Android Auto 上：** 靜靜降級。搵唔到 / 網絡唔通 / 未查完，一律顯示歌名 + 歌手大字。冇 error message、冇 retry 掣、冇「請檢查網絡」。司機唔需要知背後發生咩事，畫面保持穩定可預測。網絡返嚟下次換歌自然再試。

**手機上：** log 畫面顯示最近 N 次查詢 — 歌名、實際用嘅 query、結果（找到 / 冇 / 錯誤）、來源（memory / DB / LRCLIB）。停車或喺屋企 debug 用。

---

## 安全設計

對應 Obsidian note 嘅自建 App checklist。

### 權限

**只有兩個：**

- `INTERNET`
- `BIND_NOTIFICATION_LISTENER_SERVICE`

**明確唔要嘅：**

- `MEDIA_CONTENT_CONTROL` — 呢個係 signature-level 權限，普通 app 攞唔到。auto-lyrics 喺 manifest 宣告咗但實際冇生效。透過 NotificationListener 攞 `MediaSessionManager.getActiveSessions()` 已經足夠。少一個宣告，少一個要解釋嘅嘢。
- `RECEIVE_BOOT_COMPLETED` — NotificationListenerService 系統會自己拉返起，唔使開機自啟。
- `RECORD_AUDIO` — 完全唔做音訊分析。

### 網絡

- **只有 GET，零 POST，零 telemetry、零 analytics。**
- **只送歌名同歌手**去 LRCLIB。唔會有裝置 ID、帳號、位置、或者任何可識別資料。
- **User-Agent 寫真實資料**：app 名 + 版本 + 本 repo URL。LRCLIB 官方要求 UA 可識別。唔會出現 auto-lyrics 嗰種 UA 指去另一個 repo 嘅身份錯配。
- **唔做 cert pinning。** LRCLIB 用 Let's Encrypt，證書會轉，pin 咗反而令 app 突然壞。checklist 嘅條件（「如果 endpoint 穩定」）唔成立。靠系統 CA + `cleartextTrafficPermitted="false"`。

### App 本體

- `allowBackup="false"` — cache 冇必要備份，亦避免查詢紀錄流出 backup。
- 唔用反射、`DexClassLoader`、WebView、`exec`、Base64 payload。
- R8 只做 shrink / minify，唔做 string encryption 嗰類令人懷疑嘅嘢。
- Dependencies 用 version catalog（`libs.versions.toml`）集中管理。

### 簽名

- Keystore **絕對唔入 repo**。`.gitignore` 第一日就加 `*.jks`、`*.p12`、`*.keystore`、`local.properties`。
- `build.gradle.kts` 嘅 signing config：本地讀 `local.properties`（路徑 / 密碼 / alias），CI 讀環境變數。兩邊都**冇任何密碼寫死喺 gradle 檔**。
- debug 用 Android 預設 debug key，release 用自己生成嘅 — **兩條唔同 key**。
- Release key 嘅 SHA-256 fingerprint 記低喺 README，方便日後 `apksigner verify --print-certs` 對返。

### CI/CD

**兩個 job，權限分開：**

| Job | 觸發 | permissions | 做咩 | 掂 secrets？ |
|---|---|---|---|---|
| `build` | push、PR | `contents: read` | `assembleDebug` + unit test | 否 |
| `release` | tag push | `contents: write` | `assembleRelease` + 簽名 + 發佈 | 是 |

`build` job 因為冇 secrets，fork PR 跑到都無害。

**Supply chain 硬化：**

- 全部 action **pin commit SHA**（唔用 `@v4`），旁邊註釋寫返版本號
- `gradle-wrapper.properties` 加 `distributionSha256Sum`
- 用 `gradle/actions/wrapper-validation` 校驗 wrapper jar
- Release 附 SHA-256 checksum 檔
- 加 `actions/attest-build-provenance`

**Keystore 喺 CI：** base64 encode 放 `secrets.SIGNING_KEYSTORE_B64`，密碼 / alias 分別放另外三個 secret。CI 解碼落 runner 臨時目錄，build 完喺 `always()` cleanup step 刪走。

### README 必須寫

- NotificationListener 權限**實際用嚟做咩**、點解需要
- 「`onNotificationPosted` 只 trigger session 重掃、唔讀通知內容」呢個承諾，**加埋指返去 code 邊一行**，令人可以自己驗證
- Build 步驟、release key SHA-256 fingerprint

---

## 測試策略

### 純 JVM unit test（快、無 Android 依賴）

- **`LyricsSync`** — 最重要嗰組。邊界情況：第一行之前、最後一行之後、剛好落喺時間戳上、時間戳亂序、重複時間戳、空行、只有一行、完全冇行。
- **LRC parser** — 格式變化：`[mm:ss.xx]`、`[mm:ss.xxx]`、同一行多個時間戳、metadata 標籤（`[ar:]`、`[ti:]`）、空白行、CRLF、BOM。
- **歌名正規化** — 逐條規則測，亦要有「唔應該去」嘅 case（例如歌名本身含括號）。
- **位置推算** — 用假時鐘測：播放中、暫停、seek 後、速度非 1.0。
- **LyricsRepository 查詢順序** — 用假 source：memory hit 唔應該掂 DB、DB hit 唔應該出網絡、negative cache 未過期唔應該重查、過期要重查、網絡失敗唔應該寫 negative cache。
- **LRCLIB client** — 用 mock HTTP engine（`MockEngine` / `MockWebServer`），唔會真係打去 LRCLIB。Case：正常回應、404、timeout、500、回應格式壞、回應係純文字歌詞（無時間戳）。

### Instrumented test（要模擬器，數量少）

- Room DAO 實際讀寫（Room 需要真 SQLite）
- Car App Library screen 嘅 template 建構 — 行數上限冇爆、當前行高亮正確

### 手動驗證

- NotificationListener 實際攞到唔同播放器嘅 metadata（Spotify / YouTube Music / 本地播放器各試一次）
- Android Auto 實機顯示效果（見階段 0 spike）

---

## 實作次序

原則：**最大風險最早驗證。**

### 階段 0 — Spike：Android Auto refresh 驗證（可拋棄）

最小 app：`CarAppService` + 一個 screen，用假數據每 1.5 / 2 / 3 秒換一次「當前行」，喺 Desktop Head Unit 或實機睇。

**要答：** 換句更新流唔流暢？系統有冇壓住 refresh？template 實際容納到幾多行？

產出係**答案，唔係 code**。如果證實 throttling 太狠，要返嚟重新傾顯示策略（例如改成每兩句先更新），而唔係硬做落去。呢啲 code 明確標記丟棄。

### 階段 1 — 專案骨架 + CI

Gradle 專案、version catalog、`.gitignore`、signing config 讀 property/env、`build` job（read-only、跑 test）、wrapper validation + `distributionSha256Sum`、全部 action pin SHA。

呢階段完，每次 push 有綠燈，之後所有 code 一寫就有 CI 保護。

### 階段 2 — 核心邏輯（純 JVM，TDD）

Test-first：LRC parser → `LyricsSync` → 歌名正規化 → 位置推算。全部喺 JVM 跑，唔使模擬器。

### 階段 3 — LRCLIB client + cache

HTTP client（mock engine 測）、Room schema 同 DAO、`LyricsRepository` 查詢順序同 negative cache TTL。

### 階段 4 — MediaWatcher

NotificationListenerService、`MediaSessionManager` 訂閱、出 `Flow<PlaybackState>`。要實機／模擬器裝真播放器測 — 第一個唔可以純 JVM 驗證嘅階段。三個自我約束喺呢層寫死同埋喺 code comment 講清楚。

### 階段 5 — 手機 UI

權限引導畫面（帶用家去系統設定開 NotificationListener）+ log 畫面。

**要早過階段 6** — 冇 log 畫面，階段 6 出事只會見到 Auto 上一片空白，查唔到係 MediaWatcher 定 Repository 出問題。

### 階段 6 — Android Auto UI

`CarAppService` + `CarLyricsScreen`，接上真實 flow。套用階段 0 學到嘅顯示策略。

### 階段 7 — Release CI + README

`release` job（tag 觸發、secrets 解 keystore、`assembleRelease`、checksum、provenance、cleanup）。README 寫權限說明、build 步驟、release key fingerprint。第一個 tag build 出簽好嘅 APK，裝落實機驗證。

---

## 已知風險

| 風險 | 影響 | 應對 |
|---|---|---|
| Android Auto refresh throttling | 歌詞跳格、更新唔到 | 階段 0 spike 早驗證；「行號冇變就唔更新」已經大幅減少 refresh 次數 |
| 上唔到 Play Store | 只可以 sideload | 已接受 — 本身就係自用 |
| 歌名對唔上導致 miss | 部分歌搵唔到歌詞 | 輕度正規化 + 原名 fallback；log 畫面顯示實際 query 方便調整 |
| LRCLIB 服務中斷或消失 | 新歌查唔到（cache 過嘅仍在） | `LyricsSource` interface 令加新來源成本低；靜靜降級唔會令 app crash |
| LRCLIB 只有純文字歌詞 | 該歌當作搵唔到 | 第一版明確接受 |
