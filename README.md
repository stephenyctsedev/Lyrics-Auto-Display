# Auto Lyrics

喺 Android Auto 車機畫面顯示當前播放歌曲嘅同步歌詞，跟住播放位置逐句更新。

自用 sideload app，唔會上 Google Play。

Android Auto 對第三方 template app 有 category 白名單，「歌詞顯示」唔屬於任何一個。
所以由 v0.2.0 開始改行 **MediaBrowserService**：media 係官方支援嘅類別，將歌詞當
「可瀏覽嘅曲目清單」餵畀車機，再用 `notifyChildrenChanged()` 令佢跟住播放滾動。

由 v0.2.2 開始，喺真機（Galaxy S21）+ Desktop Head Unit 實測通過：車機列到個 app、
入到去、歌詞跟住播放順暢滾動。

---

## 點解需要「通知存取」權限

呢個 app 要用 `BIND_NOTIFICATION_LISTENER_SERVICE`（系統設定入面叫「通知存取」/
Notification access）。呢個係一個好大嘅權限，所以以下解釋要夠清楚，而且你可以自己驗證。

**點解一定要：** Android 冇提供任何官方 API 畀第三方 app 直接讀「而家播緊咩歌、播到第幾秒」。
唯一途徑係 `MediaSessionManager.getActiveSessions()`，而呢個 method 要求 caller 係一個
已授權嘅 `NotificationListenerService`。冇呢個權限就做唔到歌詞同步。

**實際用嚟做咩：** 只係攞 `MediaSessionManager`，再由佢讀歌名、歌手、播放位置。

### 三個自我約束

全部實作喺 [`NotificationMediaWatcher.kt`](app/src/main/java/com/stephen/autolyrics/media/NotificationMediaWatcher.kt)
（成個 project 只有呢一個檔案掂 NotificationListener）：

1. **`onNotificationPosted` / `onNotificationRemoved` 只用嚟觸發 media session 重新掃描。**
   Callback 入面唔讀 `StatusBarNotification` 嘅任何內容欄位 —— 唔碰 `notification.extras`、
   `tickerText`、`actions`。兩個 callback 嘅參數直接改名叫 `unused`，就係為咗一眼睇得出
   通知內容從來冇被讀取（[第 65-66 行](app/src/main/java/com/stephen/autolyrics/media/NotificationMediaWatcher.kt#L65-L66)）。

2. **唔用 `MediaController` 嘅任何控制 API**（`play()` / `pause()` / `skipToNext()` 等）。
   只讀 `metadata` 同 `playbackState`（[第 88-89 行](app/src/main/java/com/stephen/autolyrics/media/NotificationMediaWatcher.kt#L88-L89)）。
   呢個 app 純粹顯示，永遠唔會控制你嘅播放器。

3. **唔記錄、唔上傳任何通知相關資料。**

### 自己驗證（唔使信我）

```bash
# 下面每條都會排除註釋行（grep -v '^\s*[0-9]*:\s*[*/]'），
# 因為 NotificationMediaWatcher.kt 個註釋 block 本身就要講明「唔用邊啲 API」。
# 四條都應該係冇 output。

# 1. 通知內容欄位
grep -rn "extras\|tickerText\|\.actions" app/src/main/java/ \
  | grep -vE ':\s*(\*|//| \*)'

# 2. 播放控制 API
grep -rn "skipToNext\|skipToPrevious\|transportControls\|\.play()\|\.pause()" app/src/main/java/ \
  | grep -vE ':\s*(\*|//| \*)'

# 3. Logging / analytics（用 \b 開頭，唔會撞到 queryLog.entries 呢類名）
grep -rnE "\bandroid\.util\.Log\b|\bLog\.[dviwe]\(|println\(|Firebase|Analytics" app/src/main/java/ \
  | grep -vE ':\s*(\*|//| \*)'

# 4. 所有外連 URL：應該只有 lrclib.net（同埋 User-Agent 入面呢個 repo 嘅網址）
grep -rhoE 'https?://[a-zA-Z0-9./-]+' app/src/main/java/ | sort -u
```

---

## 權限清單

| 權限 | 用途 |
|---|---|
| `INTERNET` | 向 LRCLIB 查歌詞（只有 GET） |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 攞 `MediaSessionManager`（見上面） |

Manifest 入面**只有一個** `<uses-permission>`（`INTERNET`）。通知存取係經 service 嘅
`android:permission` 屬性 + intent-filter 宣告，唔係一個 `uses-permission`。

**刻意唔要嘅權限：**

- `MEDIA_CONTENT_CONTROL` —— signature-level 權限，普通 app 根本攞唔到。有啲同類 app 喺
  manifest 宣告咗，但實際上係無效嘅裝飾。少一個宣告，少一樣要解釋嘅嘢。
- `RECEIVE_BOOT_COMPLETED` —— 系統會自動拉返 `NotificationListenerService`，唔使開機自啟。
- `RECORD_AUDIO` —— 完全唔做任何音訊分析。

---

## 網絡行為

- 唯一外連目標：`https://lrclib.net/api/get`
- **只有 GET，零 POST，零 telemetry / analytics**
- 只送**歌名同歌手**兩個參數
  （[`LrclibSource.kt` 第 26-27 行](app/src/main/java/com/stephen/autolyrics/data/LrclibSource.kt#L26-L27)）。
  冇裝置 ID、冇帳號、冇位置、冇任何可識別資料。
- User-Agent 寫真實資料（app 名 + 版本 + 本 repo 網址），符合 LRCLIB 對 caller 自我識別嘅要求
- **唔做 cert pinning** —— LRCLIB 用 Let's Encrypt，證書會轉，pin 咗反而會令 app 突然壞。
  靠系統 CA + manifest 嘅 `usesCleartextTraffic="false"`
- `allowBackup="false"` —— 查詢紀錄唔會流出 backup

歌詞 cache 喺 app 私有嘅 Room database，唔會離開部機。手機畫面嗰個查詢紀錄（debug 用）
只存喺記憶體，最多 50 條，唔會寫落 disk 亦唔會上傳。

---

## Build

### 本地

1. 生成 release keystore（**唔好** commit）：

   ```bash
   keytool -genkeypair -v \
     -keystore ~/.android/keystores/autolyrics-release.jks \
     -alias autolyrics -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=Auto Lyrics, O=Personal, C=HK"
   ```

2. 喺 `local.properties` 加（呢個檔已經喺 `.gitignore`）：

   ```properties
   signing.storeFile=/absolute/path/to/autolyrics-release.jks
   signing.storePassword=...
   signing.keyAlias=autolyrics
   signing.keyPassword=...
   ```

3. Build：

   ```bash
   ./gradlew assembleRelease
   ```

冇配置 keystore 嘅話，`assembleRelease` 會出 `AutoLyrics-v<版本>-unsigned.apk` ——
**唔會**靜靜跌返用 debug key 簽。debug 同 release 用兩條唔同嘅 key。

### CI

- **`build.yml`**（push / PR）：`permissions: contents: read`，唔掂任何 secret，
  只跑 unit test + `assembleDebug`。因為冇 secrets，fork PR 跑到都無害。**唔會派 APK。**
- **`release.yml`**（push tag `v*`）：`contents: write` 只喺呢個 job 開。由 secrets 解 keystore，
  跑 `assembleRelease`，用 `apksigner verify` 確認真係簽咗先派，附 SHA-256 checksum 同
  build provenance，最後喺 `always()` step 刪走 keystore。

全部 GitHub Action 都 pin 咗 commit SHA（唔用 `@v4` 呢類 mutable tag），
`gradle-wrapper.properties` 有 `distributionSha256Sum`，CI 亦有 wrapper jar 校驗。

需要設定嘅 GitHub secrets：`SIGNING_KEYSTORE_B64`（keystore base64）、
`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。

---

## 驗證你手上嘅 APK

```bash
apksigner verify --print-certs AutoLyrics-v0.2.0.apk
sha256sum -c AutoLyrics-v0.2.0.apk.sha256
```

Release key SHA-256 fingerprint：

```
46:24:28:11:4F:FB:13:DF:E7:5D:1D:96:A5:04:95:1D:37:1C:0E:0C:F2:33:43:AC:22:FA:27:5A:BB:96:25:55
```

自己核對（注意：**唔好**加 `| grep`／`>` 重導向，否則 keytool 攞唔到 console，
密碼會明文顯示喺畫面）：

```bash
keytool -list -v -keystore ~/.android/keystores/autolyrics-release.jks -alias autolyrics
```

---

## 架構

```
MediaWatcher (NotificationListener + MediaSessionManager)
    ↓ Flow<PlaybackState>   歌名 / 歌手 / 位置 / 播放中
LyricsRepository            memory → Room → LRCLIB
    ↓ ParsedLyrics
LyricsSync (純函數：位置 → 第幾行)
    ↓
LyricsFeed                  查詢 + 250ms tick 推算位置；行號變咗先出新 state
    ↓ StateFlow<LyricsFeedState>
LyricsBrowserService (車機)  +  MainActivity (手機)
```

手機同車機**共用同一個 `LyricsFeed`**，所以手機見到咩，車機就顯示咩 ——
喺手機度就驗證到大部分嘢，唔使次次接車。

`lyrics/` 同 `media/PositionEstimator.kt` 零 Android 依賴，所以喺普通 JVM test 就測得晒
（66 個 unit test），唔使開模擬器。Room DAO 另外有 5 個 instrumented test。

**Cache 策略：** 搵到嘅歌詞永久保留（歌詞唔會變）；LRCLIB 明確答「冇」就記 7 日
（畀佢有機會之後補上）；**網絡失敗／伺服器繁忙唔會寫 cache** —— 呢點好重要，
因為 LRCLIB 幾容易回 503，如果當成「冇歌詞」就會白白封住一首歌七日。

**歌名正規化：** metadata 成日有 `(Remastered 2011)`、`- Live` 呢類後綴，直接查會 miss，
所以會先做輕度正規化再查；查唔到就用未切後綴嘅全名再試一次。手機嗰個查詢紀錄畫面
會顯示實際用咗咩 query，方便日後調整。

---

## 已知限制

- **只支援有時間戳嘅歌詞。** LRCLIB 只有純文字歌詞嗰啲歌會當搵唔到處理 ——
  喺車機顯示一大段唔會郁嘅文字反而係視覺干擾。
- **多個播放器同時開住**嘅時候，app 揀 active session 列表第一個，可能唔係你估嗰個。
- 搵唔到歌詞 / 網絡唔通嗰陣，車機只顯示歌名同歌手，**唔會出 error 或者 retry 掣** ——
  行車時閃動嘅文字係安全問題，而且司機都做唔到啲咩。
