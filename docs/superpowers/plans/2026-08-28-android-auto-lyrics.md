# Android Auto 歌詞顯示 App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一個 Android app，喺 Android Auto 車機畫面顯示當前播放歌曲嘅同步歌詞，跟住播放位置逐句更新。

**Architecture:** 四個模組用窄介面連住 —— `MediaWatcher`（唯一掂 NotificationListener，出 `Flow<PlaybackState>`）、`LyricsRepository`（memory → Room → LRCLIB）、`LyricsSync`（純函數，位置 → 第幾行）、兩個互不知情嘅 UI（Android Auto template + 手機權限引導/log）。核心邏輯零 Android 依賴，喺 JVM 上測。

**Tech Stack:** Kotlin、Gradle Kotlin DSL、`androidx.car.app:app` 1.7.0（stable）、Room、OkHttp + `MockWebServer`、kotlinx.coroutines（Flow）、JUnit4、Jetpack Compose（手機 UI）、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-08-28-android-auto-lyrics-design.md`

## Global Constraints

呢一節嘅要求隱含喺**每一個** task 入面。

- **Package name:** `com.stephen.autolyrics`
- **權限只有兩個：** `INTERNET`、`BIND_NOTIFICATION_LISTENER_SERVICE`。**唔准**加 `MEDIA_CONTENT_CONTROL`、`RECEIVE_BOOT_COMPLETED`、`RECORD_AUDIO` 或任何其他權限。
- **Keystore 絕對唔入 repo。** `.gitignore` 必須有 `*.jks`、`*.p12`、`*.keystore`、`local.properties`。任何密碼／alias **唔准**寫入 `build.gradle.kts`。
- **只有 GET，零 POST，零 telemetry / analytics。** 送去 LRCLIB 嘅只有歌名同歌手，唔准有裝置 ID、帳號、位置。
- **唔准用**反射、`DexClassLoader`、WebView、`exec`、Base64 payload、string encryption。
- `allowBackup="false"`、`cleartextTrafficPermitted="false"`。
- **唔做 cert pinning**（LRCLIB 用 Let's Encrypt，證書會轉）。
- **全部 GitHub Action pin commit SHA**，旁邊註釋寫版本號。唔准用 `@v4` 呢類 mutable tag。
- Dependencies 一律經 version catalog `gradle/libs.versions.toml`。
- **minSdk 23、targetSdk 35、compileSdk 35**、JDK 17。
- **Test fixture 唔准用真實歌詞**（版權）。一律用 `"line one"`、`"line two"` 呢類 placeholder 文字。

### LRCLIB API 契約（2026-08-28 實測確認）

```
GET https://lrclib.net/api/get?artist_name={artist}&track_name={track}
```

| 情況 | HTTP | Body |
|---|---|---|
| 搵到 | 200 | `{id, name, trackName, artistName, albumName, duration, instrumental, plainLyrics, syncedLyrics}` |
| 明確搵唔到 | 404 | `{"message":"Failed to find specified track","name":"TrackNotFound","statusCode":404}` |
| 伺服器繁忙 | 503 | `{"message":"The server is busy, please retry in a moment","name":"ServerOverloaded","statusCode":503}` |

- `syncedLyrics` 係 `[mm:ss.xx]` 前綴嘅字串，**可以係 `null`**（得 `plainLyrics`）。
- `instrumental` 係 boolean。
- **關鍵：** 503 喺實測第一次 call 就撞到，唔罕見。**只有 404 先可以寫 negative cache**，503 / timeout / 其他錯誤一律唔准寫。

---

## File Structure

```
app/src/main/java/com/stephen/autolyrics/
  lyrics/
    LrcParser.kt          # LRC 文字 → ParsedLyrics
    ParsedLyrics.kt       # data class：LyricLine 列表
    LyricsSync.kt         # 純函數：位置 → 第幾行
    TrackKey.kt           # 歌名+歌手，含正規化
  data/
    LyricsSource.kt       # interface + LyricsResult sealed interface
    LrclibSource.kt       # OkHttp client，實作 LyricsSource
    LyricsDatabase.kt     # Room database
    LyricsEntity.kt       # Room entity + DAO
    LyricsRepository.kt   # memory → DB → network 順序
    QueryLog.kt           # debug log（in-memory ring buffer）
  media/
    PlaybackState.kt      # data class
    MediaWatcher.kt       # interface
    NotificationMediaWatcher.kt  # NotificationListenerService 實作
    PositionEstimator.kt  # 純函數：推算當前位置
  car/
    LyricsCarAppService.kt
    LyricsSession.kt
    CarLyricsScreen.kt
  phone/
    MainActivity.kt       # 權限引導 + log 畫面
app/src/test/java/...     # 對應嘅 JVM test
app/src/androidTest/java/...  # Room DAO + template test
```

**檔案職責邊界：** `lyrics/` 同 `media/PositionEstimator.kt` 零 Android 依賴（純 Kotlin，JVM 可測）。`data/` 只有 Room 同 OkHttp。`media/NotificationMediaWatcher.kt` 係唯一掂 NotificationListener 嘅檔案。`car/` 同 `phone/` 互不引用。

---

## Task 1: 專案骨架 + CI（read-only build job）

**Files:**
- Create: `settings.gradle.kts`、`build.gradle.kts`、`app/build.gradle.kts`、`gradle/libs.versions.toml`、`.gitignore`、`app/src/main/AndroidManifest.xml`、`.github/workflows/build.yml`、`gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Consumes: 冇（第一個 task）
- Produces: 可 build 嘅 Gradle 專案；`./gradlew test` 同 `./gradlew assembleDebug` 行得通；CI 綠燈。

- [ ] **Step 1: 建立 `.gitignore`**

```gitignore
*.jks
*.p12
*.keystore
local.properties
.gradle/
build/
app/build/
.idea/
*.iml
.DS_Store
captures/
```

- [ ] **Step 2: 建立 version catalog `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
coroutines = "1.9.0"
carApp = "1.7.0"
room = "2.6.1"
okhttp = "4.12.0"
lifecycle = "2.8.7"
composeBom = "2024.10.01"
activityCompose = "1.9.3"
junit = "4.13.2"
robolectric = "4.14"
androidxTestJunit = "1.2.1"
androidxTestRunner = "1.6.2"

[libraries]
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
car-app-projected = { module = "androidx.car.app:app-projected", version.ref = "carApp" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
lifecycle-service = { module = "androidx.lifecycle:lifecycle-service", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestJunit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version = "2.0.21-1.0.28" }
```

- [ ] **Step 3: 建立 `settings.gradle.kts` 同 root `build.gradle.kts`**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AutoLyrics"
include(":app")
```

root `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 4: 建立 `app/build.gradle.kts`（signing 讀 property/env，零硬編碼密碼）**

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 本地讀 local.properties，CI 讀環境變數。兩邊都唔會有密碼入 repo。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? =
    localProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.stephen.autolyrics"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stephen.autolyrics"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePathValue = secret("signing.storeFile", "SIGNING_STORE_FILE")
            if (storePathValue != null) {
                storeFile = file(storePathValue)
                storePassword = secret("signing.storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = secret("signing.keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = secret("signing.keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 只有喺 keystore 真係配置咗先簽，否則留返 unsigned（唔會靜靜跌返 debug key）
            signingConfig = if (secret("signing.storeFile", "SIGNING_STORE_FILE") != null)
                signingConfigs.getByName("release") else null
        }
        debug {
            // 明確用 Android 預設 debug key —— 同 release key 唔同
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.car.app.projected)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
```

- [ ] **Step 5: 建立最小 `AndroidManifest.xml`（只有兩個權限）**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 只有兩個權限。刻意唔要 MEDIA_CONTENT_CONTROL（signature-level，普通 app 攞唔到）、
         RECEIVE_BOOT_COMPLETED（系統會自動拉返 NotificationListenerService）、RECORD_AUDIO。 -->
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="false"
        android:label="Auto Lyrics"
        android:usesCleartextTraffic="false"
        android:supportsRtl="true">
    </application>
</manifest>
```

- [ ] **Step 6: 產生 Gradle wrapper 並加 `distributionSha256Sum`**

Run: `gradle wrapper --gradle-version 8.11.1`

跟住喺 `gradle/wrapper/gradle-wrapper.properties` 加最後一行（SHA-256 由 https://gradle.org/release-checksums/ 嘅 "Binary-only (-bin) ZIP Checksum" 攞，要對應 8.11.1）：

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionSha256Sum=<由 gradle.org/release-checksums 抄返 8.11.1 -bin 嘅值>
```

- [ ] **Step 7: 建立 CI build job（read-only，零 secrets）**

`.github/workflows/build.yml`:

```yaml
name: build

on:
  push:
    branches: [main]
  pull_request:

# 預設收到最細；release job 喺 Task 12 另開一個 workflow 先開 contents: write
permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      # actions/checkout v4.2.2
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683

      # actions/setup-java v4.5.0
      - uses: actions/setup-java@8df1039502a15bceb9433410b1a100fbe190c53b
        with:
          distribution: temurin
          java-version: '17'

      # gradle/actions/wrapper-validation v4.2.1 —— 校驗 wrapper jar 冇被塞嘢
      - uses: gradle/actions/wrapper-validation@cc4fc85e6b35bafd578d5ffbc76a5518407e1af0

      # gradle/actions/setup-gradle v4.2.1
      - uses: gradle/actions/setup-gradle@cc4fc85e6b35bafd578d5ffbc76a5518407e1af0

      - name: Run unit tests
        run: ./gradlew test --no-daemon

      - name: Assemble debug
        run: ./gradlew assembleDebug --no-daemon
```

> **注意：** 上面每個 SHA 都要喺加入前自己核對（`gh api repos/actions/checkout/git/ref/tags/v4.2.2`）。SHA 錯咗 CI 會直接 fail，唔會靜靜用錯版本。

- [ ] **Step 8: 驗證 build 通過**

Run: `./gradlew assembleDebug test --no-daemon`
Expected: BUILD SUCCESSFUL（未有 test，所以 test task 會 no-op 或 UP-TO-DATE）

- [ ] **Step 9: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts app/ gradle/ .github/ gradlew gradlew.bat
git commit -m "chore: scaffold Gradle project with hardened CI"
```

---

## Task 2: LRC parser

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/lyrics/ParsedLyrics.kt`、`app/src/main/java/com/stephen/autolyrics/lyrics/LrcParser.kt`
- Test: `app/src/test/java/com/stephen/autolyrics/lyrics/LrcParserTest.kt`

**Interfaces:**
- Consumes: 冇
- Produces:
  - `data class LyricLine(val timeMs: Long, val text: String)`
  - `data class ParsedLyrics(val lines: List<LyricLine>)`
  - `object LrcParser { fun parse(raw: String): ParsedLyrics }`

- [ ] **Step 1: 寫失敗測試**

`app/src/test/java/com/stephen/autolyrics/lyrics/LrcParserTest.kt`:

```kotlin
package com.stephen.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses two-digit centisecond timestamps`() {
        val parsed = LrcParser.parse("[00:12.34]line one")
        assertEquals(1, parsed.lines.size)
        assertEquals(12_340L, parsed.lines[0].timeMs)
        assertEquals("line one", parsed.lines[0].text)
    }

    @Test
    fun `parses three-digit millisecond timestamps`() {
        val parsed = LrcParser.parse("[00:12.345]line one")
        assertEquals(12_345L, parsed.lines[0].timeMs)
    }

    @Test
    fun `parses minutes correctly`() {
        val parsed = LrcParser.parse("[02:05.00]line one")
        assertEquals(125_000L, parsed.lines[0].timeMs)
    }

    @Test
    fun `expands multiple timestamps on one line into multiple entries`() {
        val parsed = LrcParser.parse("[00:10.00][00:20.00]repeated line")
        assertEquals(2, parsed.lines.size)
        assertEquals(10_000L, parsed.lines[0].timeMs)
        assertEquals(20_000L, parsed.lines[1].timeMs)
        assertEquals("repeated line", parsed.lines[1].text)
    }

    @Test
    fun `skips metadata tags`() {
        val parsed = LrcParser.parse("[ar:Some Artist]\n[ti:Some Title]\n[00:01.00]line one")
        assertEquals(1, parsed.lines.size)
        assertEquals("line one", parsed.lines[0].text)
    }

    @Test
    fun `sorts out-of-order timestamps`() {
        val parsed = LrcParser.parse("[00:20.00]second\n[00:10.00]first")
        assertEquals(listOf("first", "second"), parsed.lines.map { it.text })
    }

    @Test
    fun `handles CRLF line endings`() {
        val parsed = LrcParser.parse("[00:01.00]line one\r\n[00:02.00]line two")
        assertEquals(2, parsed.lines.size)
        assertEquals("line one", parsed.lines[0].text)
    }

    @Test
    fun `strips UTF-8 BOM`() {
        val parsed = LrcParser.parse("﻿[00:01.00]line one")
        assertEquals(1, parsed.lines.size)
        assertEquals("line one", parsed.lines[0].text)
    }

    @Test
    fun `keeps timestamped blank lines as empty text`() {
        val parsed = LrcParser.parse("[00:01.00]line one\n[00:05.00]\n[00:09.00]line two")
        assertEquals(3, parsed.lines.size)
        assertEquals("", parsed.lines[1].text)
    }

    @Test
    fun `ignores lines with no timestamp`() {
        val parsed = LrcParser.parse("just plain text\n[00:01.00]line one")
        assertEquals(1, parsed.lines.size)
    }

    @Test
    fun `returns empty for blank input`() {
        assertTrue(LrcParser.parse("").lines.isEmpty())
        assertTrue(LrcParser.parse("   \n  ").lines.isEmpty())
    }

    @Test
    fun `trims surrounding whitespace from text`() {
        val parsed = LrcParser.parse("[00:01.00]   line one   ")
        assertEquals("line one", parsed.lines[0].text)
    }
}
```

- [ ] **Step 2: 行測試確認佢 fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*LrcParserTest*'`
Expected: FAIL —— compile error，`LrcParser` / `ParsedLyrics` unresolved

- [ ] **Step 3: 寫最小實作**

`ParsedLyrics.kt`:

```kotlin
package com.stephen.autolyrics.lyrics

data class LyricLine(val timeMs: Long, val text: String)

data class ParsedLyrics(val lines: List<LyricLine>) {
    val isEmpty: Boolean get() = lines.isEmpty()
}
```

`LrcParser.kt`:

```kotlin
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
```

- [ ] **Step 4: 行測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests '*LrcParserTest*'`
Expected: PASS（12 個 test）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/lyrics/ app/src/test/java/com/stephen/autolyrics/lyrics/
git commit -m "feat: add LRC parser"
```

---

## Task 3: LyricsSync（位置 → 第幾行）

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/lyrics/LyricsSync.kt`
- Test: `app/src/test/java/com/stephen/autolyrics/lyrics/LyricsSyncTest.kt`

**Interfaces:**
- Consumes: `ParsedLyrics`、`LyricLine`（Task 2）
- Produces: `object LyricsSync { fun currentLineIndex(lyrics: ParsedLyrics, positionMs: Long): Int? }`
  —— 回傳 `null` 代表「第一句之前」或者「完全冇歌詞」。

- [ ] **Step 1: 寫失敗測試**

```kotlin
package com.stephen.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsSyncTest {

    private fun lyricsOf(vararg times: Long) =
        ParsedLyrics(times.mapIndexed { i, t -> LyricLine(t, "line $i") })

    @Test
    fun `returns null before the first line`() {
        assertNull(LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 1_000))
    }

    @Test
    fun `returns first line exactly on its timestamp`() {
        assertEquals(0, LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 5_000))
    }

    @Test
    fun `returns the line whose window contains the position`() {
        assertEquals(0, LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 7_000))
        assertEquals(1, LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 10_500))
    }

    @Test
    fun `holds the last line after it starts`() {
        assertEquals(1, LyricsSync.currentLineIndex(lyricsOf(5_000, 10_000), 9_999_000))
    }

    @Test
    fun `returns null for empty lyrics`() {
        assertNull(LyricsSync.currentLineIndex(ParsedLyrics(emptyList()), 1_000))
    }

    @Test
    fun `handles single line lyrics`() {
        assertNull(LyricsSync.currentLineIndex(lyricsOf(5_000), 4_999))
        assertEquals(0, LyricsSync.currentLineIndex(lyricsOf(5_000), 5_000))
    }

    @Test
    fun `returns the last of duplicate timestamps`() {
        val lyrics = ParsedLyrics(listOf(
            LyricLine(5_000, "line a"),
            LyricLine(5_000, "line b"),
            LyricLine(9_000, "line c"),
        ))
        assertEquals(1, LyricsSync.currentLineIndex(lyrics, 5_000))
    }

    @Test
    fun `handles negative position defensively`() {
        assertNull(LyricsSync.currentLineIndex(lyricsOf(0, 5_000), -100))
    }

    @Test
    fun `handles a line at time zero`() {
        assertEquals(0, LyricsSync.currentLineIndex(lyricsOf(0, 5_000), 0))
    }
}
```

- [ ] **Step 2: 行測試確認 fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*LyricsSyncTest*'`
Expected: FAIL —— `LyricsSync` unresolved

- [ ] **Step 3: 寫實作（binary search）**

```kotlin
package com.stephen.autolyrics.lyrics

/**
 * 純函數，零 Android 依賴：畀歌詞同當前毫秒數，答而家係第幾行。
 * 回傳 null = 第一句仲未到（或者冇歌詞）。
 */
object LyricsSync {

    fun currentLineIndex(lyrics: ParsedLyrics, positionMs: Long): Int? {
        val lines = lyrics.lines
        if (lines.isEmpty()) return null
        if (positionMs < lines.first().timeMs) return null

        // 搵最後一個 timeMs <= positionMs 嘅 index
        var low = 0
        var high = lines.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
```

- [ ] **Step 4: 行測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests '*LyricsSyncTest*'`
Expected: PASS（9 個 test）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/lyrics/LyricsSync.kt app/src/test/java/com/stephen/autolyrics/lyrics/LyricsSyncTest.kt
git commit -m "feat: add lyrics position-to-line sync"
```

---

## Task 4: TrackKey 同歌名正規化

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/lyrics/TrackKey.kt`
- Test: `app/src/test/java/com/stephen/autolyrics/lyrics/TrackKeyTest.kt`

**Interfaces:**
- Consumes: 冇
- Produces:
  - `data class TrackKey(val title: String, val artist: String)`
  - `TrackKey.normalized(): TrackKey` —— 去後綴、統一大細階同空白
  - `TrackKey.cacheKey(): String` —— Room 主鍵用

- [ ] **Step 1: 寫失敗測試**

```kotlin
package com.stephen.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackKeyTest {

    private fun norm(title: String, artist: String = "Some Artist") =
        TrackKey(title, artist).normalized().title

    @Test
    fun `removes remaster suffix in parentheses`() {
        assertEquals("song name", norm("Song Name (Remastered 2011)"))
    }

    @Test
    fun `removes live suffix after dash`() {
        assertEquals("song name", norm("Song Name - Live"))
    }

    @Test
    fun `removes feat suffix`() {
        assertEquals("song name", norm("Song Name (feat. Other Artist)"))
    }

    @Test
    fun `removes bracketed remix marker`() {
        assertEquals("song name", norm("Song Name [Radio Edit]"))
    }

    @Test
    fun `keeps parentheses that are part of the actual title`() {
        // 括號內容唔喺已知雜訊清單，要保留
        assertEquals("song name (reprise)", norm("Song Name (Reprise)"))
    }

    @Test
    fun `collapses repeated whitespace and lowercases`() {
        assertEquals("song name", norm("  Song   NAME  "))
    }

    @Test
    fun `normalizes the artist too`() {
        val k = TrackKey("Song", "  The ARTIST ").normalized()
        assertEquals("the artist", k.artist)
    }

    @Test
    fun `leaves a clean title unchanged`() {
        assertEquals("song name", norm("Song Name"))
    }

    @Test
    fun `does not strip the whole title when it is only a suffix`() {
        // 全個名都係括號 → 唔應該變空字串
        assertEquals("(remastered 2011)", norm("(Remastered 2011)"))
    }

    @Test
    fun `cacheKey joins artist and title case-insensitively`() {
        assertEquals(
            TrackKey("Song Name", "Artist").cacheKey(),
            TrackKey("song name", "artist").cacheKey(),
        )
    }
}
```

- [ ] **Step 2: 行測試確認 fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TrackKeyTest*'`
Expected: FAIL —— `TrackKey` unresolved

- [ ] **Step 3: 寫實作**

```kotlin
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
```

- [ ] **Step 4: 行測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests '*TrackKeyTest*'`
Expected: PASS（10 個 test）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/lyrics/TrackKey.kt app/src/test/java/com/stephen/autolyrics/lyrics/TrackKeyTest.kt
git commit -m "feat: add track key normalization"
```

---

## Task 5: PositionEstimator（本地推算播放位置）

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/media/PlaybackState.kt`、`app/src/main/java/com/stephen/autolyrics/media/PositionEstimator.kt`
- Test: `app/src/test/java/com/stephen/autolyrics/media/PositionEstimatorTest.kt`

**Interfaces:**
- Consumes: 冇
- Produces:
  - `data class PlaybackState(title, artist, album, positionMs, positionUpdateTimeMs, playbackSpeed, isPlaying)`
  - `object PositionEstimator { fun estimate(state: PlaybackState, nowMs: Long): Long }`

- [ ] **Step 1: 寫失敗測試**

```kotlin
package com.stephen.autolyrics.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionEstimatorTest {

    private fun state(
        positionMs: Long = 10_000,
        updateTime: Long = 1_000,
        speed: Float = 1.0f,
        playing: Boolean = true,
    ) = PlaybackState(
        title = "t", artist = "a", album = null,
        positionMs = positionMs,
        positionUpdateTimeMs = updateTime,
        playbackSpeed = speed,
        isPlaying = playing,
    )

    @Test
    fun `advances position by elapsed time while playing`() {
        // 報告時 position=10s；之後過咗 2s → 12s
        assertEquals(12_000L, PositionEstimator.estimate(state(), nowMs = 3_000))
    }

    @Test
    fun `does not advance while paused`() {
        val paused = state(playing = false)
        assertEquals(10_000L, PositionEstimator.estimate(paused, nowMs = 99_000))
    }

    @Test
    fun `respects playback speed`() {
        // 1.5x：過咗 2s 實際行咗 3s
        assertEquals(13_000L, PositionEstimator.estimate(state(speed = 1.5f), nowMs = 3_000))
    }

    @Test
    fun `uses the new baseline after a seek`() {
        // seek 到 60s，報告時間 5_000
        val seeked = state(positionMs = 60_000, updateTime = 5_000)
        assertEquals(61_000L, PositionEstimator.estimate(seeked, nowMs = 6_000))
    }

    @Test
    fun `returns the reported position when no time has elapsed`() {
        assertEquals(10_000L, PositionEstimator.estimate(state(), nowMs = 1_000))
    }

    @Test
    fun `clamps negative elapsed time to the reported position`() {
        // now 早過 update time（時鐘跳動）→ 唔應該倒退
        assertEquals(10_000L, PositionEstimator.estimate(state(), nowMs = 500))
    }

    @Test
    fun `treats zero speed as paused`() {
        assertEquals(10_000L, PositionEstimator.estimate(state(speed = 0f), nowMs = 99_000))
    }
}
```

- [ ] **Step 2: 行測試確認 fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*PositionEstimatorTest*'`
Expected: FAIL —— unresolved reference

- [ ] **Step 3: 寫實作**

`PlaybackState.kt`:

```kotlin
package com.stephen.autolyrics.media

/**
 * 播放狀態快照。
 *
 * positionUpdateTimeMs 係「報告 positionMs 嗰一刻」嘅 SystemClock.elapsedRealtime()，
 * 唔係 wall clock —— 推算位置直接靠佢做基準點。
 */
data class PlaybackState(
    val title: String,
    val artist: String,
    val album: String?,
    val positionMs: Long,
    val positionUpdateTimeMs: Long,
    val playbackSpeed: Float,
    val isPlaying: Boolean,
)
```

`PositionEstimator.kt`:

```kotlin
package com.stephen.autolyrics.media

/**
 * 本地推算當前播放位置，避免每個 tick 都去問系統。
 * 估算位置 = positionMs + (now - positionUpdateTimeMs) × playbackSpeed
 */
object PositionEstimator {

    fun estimate(state: PlaybackState, nowMs: Long): Long {
        if (!state.isPlaying || state.playbackSpeed <= 0f) return state.positionMs
        val elapsed = nowMs - state.positionUpdateTimeMs
        if (elapsed <= 0) return state.positionMs
        return state.positionMs + (elapsed * state.playbackSpeed).toLong()
    }
}
```

- [ ] **Step 4: 行測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests '*PositionEstimatorTest*'`
Expected: PASS（7 個 test）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/media/ app/src/test/java/com/stephen/autolyrics/media/
git commit -m "feat: add playback position estimator"
```

---

## Task 6: LyricsSource interface + LRCLIB client

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/data/LyricsSource.kt`、`app/src/main/java/com/stephen/autolyrics/data/LrclibSource.kt`
- Test: `app/src/test/java/com/stephen/autolyrics/data/LrclibSourceTest.kt`

**Interfaces:**
- Consumes: `TrackKey`（Task 4）、`ParsedLyrics` / `LrcParser`（Task 2）
- Produces:
  - `sealed interface LyricsResult { Found(lyrics, source) / NotFound / Error(reason) }`
  - `enum class LyricsOrigin { MEMORY, DATABASE, NETWORK }`
  - `interface LyricsSource { suspend fun lookup(key: TrackKey): LyricsResult }`
  - `class LrclibSource(baseUrl: String, client: OkHttpClient, userAgent: String) : LyricsSource`

- [ ] **Step 1: 寫失敗測試（用 MockWebServer，唔會真係打去 LRCLIB）**

```kotlin
package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.TrackKey
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LrclibSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: LrclibSource

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        source = LrclibSource(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient.Builder()
                .callTimeout(1, TimeUnit.SECONDS)
                .build(),
            userAgent = "AutoLyrics/0.1.0 (https://github.com/stephenyctsedev/auto-lyrics)",
        )
    }

    @After fun tearDown() = server.shutdown()

    private val syncedBody = """
        {"id":1,"trackName":"Song","artistName":"Artist","albumName":"Album",
         "duration":100.0,"instrumental":false,
         "plainLyrics":"line one\nline two",
         "syncedLyrics":"[00:01.00]line one\n[00:05.00]line two"}
    """.trimIndent()

    @Test
    fun `returns Found with parsed synced lyrics on 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncedBody))
        val result = source.lookup(TrackKey("Song", "Artist"))
        assertTrue(result is LyricsResult.Found)
        val found = result as LyricsResult.Found
        assertEquals(2, found.lyrics.lines.size)
        assertEquals("line one", found.lyrics.lines[0].text)
        assertEquals(LyricsOrigin.NETWORK, found.origin)
    }

    @Test
    fun `sends artist and track as query params with the user agent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncedBody))
        source.lookup(TrackKey("Song Name", "Artist Name"))
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/api/get", url.encodedPath)
        assertEquals("Song Name", url.queryParameter("track_name"))
        assertEquals("Artist Name", url.queryParameter("artist_name"))
        assertTrue(request.getHeader("User-Agent")!!.contains("AutoLyrics"))
    }

    @Test
    fun `returns NotFound on 404 TrackNotFound`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody(
            """{"message":"Failed to find specified track","name":"TrackNotFound","statusCode":404}"""
        ))
        assertEquals(LyricsResult.NotFound, source.lookup(TrackKey("Song", "Artist")))
    }

    @Test
    fun `returns Error not NotFound on 503 ServerOverloaded`() = runBlocking {
        // 關鍵：503 唔可以當成「冇呢首歌」，否則會被 negative cache 封 7 日
        server.enqueue(MockResponse().setResponseCode(503).setBody(
            """{"message":"The server is busy, please retry in a moment","name":"ServerOverloaded","statusCode":503}"""
        ))
        assertTrue(source.lookup(TrackKey("Song", "Artist")) is LyricsResult.Error)
    }

    @Test
    fun `returns Error on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertTrue(source.lookup(TrackKey("Song", "Artist")) is LyricsResult.Error)
    }

    @Test
    fun `returns Error on malformed json`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not json"))
        assertTrue(source.lookup(TrackKey("Song", "Artist")) is LyricsResult.Error)
    }

    @Test
    fun `returns NotFound when syncedLyrics is null`() = runBlocking {
        // 只有純文字歌詞 → 第一版當搵唔到（冇時間戳跟唔到秒數）
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":1,"trackName":"Song","artistName":"Artist","instrumental":false,
                "plainLyrics":"line one","syncedLyrics":null}"""
        ))
        assertEquals(LyricsResult.NotFound, source.lookup(TrackKey("Song", "Artist")))
    }

    @Test
    fun `returns NotFound for instrumental tracks`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":1,"trackName":"Song","artistName":"Artist","instrumental":true,
                "plainLyrics":null,"syncedLyrics":null}"""
        ))
        assertEquals(LyricsResult.NotFound, source.lookup(TrackKey("Song", "Artist")))
    }

    @Test
    fun `returns NotFound when syncedLyrics parses to zero lines`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":1,"trackName":"Song","artistName":"Artist","instrumental":false,
                "plainLyrics":"x","syncedLyrics":"no timestamps here"}"""
        ))
        assertEquals(LyricsResult.NotFound, source.lookup(TrackKey("Song", "Artist")))
    }

    @Test
    fun `returns Error on timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(
            okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        assertTrue(source.lookup(TrackKey("Song", "Artist")) is LyricsResult.Error)
    }
}
```

- [ ] **Step 2: 行測試確認 fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*LrclibSourceTest*'`
Expected: FAIL —— `LrclibSource` unresolved

- [ ] **Step 3: 寫實作**

`LyricsSource.kt`:

```kotlin
package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.ParsedLyrics
import com.stephen.autolyrics.lyrics.TrackKey

enum class LyricsOrigin { MEMORY, DATABASE, NETWORK }

sealed interface LyricsResult {
    /** 搵到有時間戳嘅歌詞。 */
    data class Found(val lyrics: ParsedLyrics, val origin: LyricsOrigin) : LyricsResult

    /** 明確搵唔到（LRCLIB 答 404、或者只有純文字／純音樂）。可以 negative cache。 */
    data object NotFound : LyricsResult

    /** 暫時性失敗（網絡、503、格式壞）。**唔可以** negative cache。 */
    data class Error(val reason: String) : LyricsResult
}

interface LyricsSource {
    suspend fun lookup(key: TrackKey): LyricsResult
}
```

`LrclibSource.kt`:

```kotlin
package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.LrcParser
import com.stephen.autolyrics.lyrics.TrackKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * LRCLIB 查詢。只做 GET，只送歌名同歌手 —— 冇裝置 ID、冇帳號、冇位置。
 *
 * 唔做 cert pinning：LRCLIB 用 Let's Encrypt，證書會轉，pin 咗會令 app 突然壞。
 * 靠系統 CA + manifest 嘅 usesCleartextTraffic=false。
 */
class LrclibSource(
    private val baseUrl: String = "https://lrclib.net",
    private val client: OkHttpClient,
    private val userAgent: String,
) : LyricsSource {

    override suspend fun lookup(key: TrackKey): LyricsResult = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", key.artist)
            .addQueryParameter("track_name", key.title)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> LyricsResult.NotFound
                    !response.isSuccessful ->
                        LyricsResult.Error("HTTP ${response.code}")
                    else -> parseBody(response.body?.string().orEmpty())
                }
            }
        } catch (e: Exception) {
            LyricsResult.Error(e.javaClass.simpleName + ": " + (e.message ?: "network failure"))
        }
    }

    private fun parseBody(body: String): LyricsResult = try {
        val json = JSONObject(body)
        val synced = if (json.isNull("syncedLyrics")) null else json.getString("syncedLyrics")
        val instrumental = json.optBoolean("instrumental", false)

        when {
            instrumental || synced.isNullOrBlank() -> LyricsResult.NotFound
            else -> {
                val parsed = LrcParser.parse(synced)
                // 冇時間戳 = 跟唔到秒數，當搵唔到處理
                if (parsed.isEmpty) LyricsResult.NotFound
                else LyricsResult.Found(parsed, LyricsOrigin.NETWORK)
            }
        }
    } catch (e: Exception) {
        LyricsResult.Error("malformed response")
    }
}
```

- [ ] **Step 4: 行測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests '*LrclibSourceTest*'`
Expected: PASS（10 個 test）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/data/ app/src/test/java/com/stephen/autolyrics/data/
git commit -m "feat: add LRCLIB lyrics source"
```

---

## Task 7: Room cache（entity + DAO + database）

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/data/LyricsEntity.kt`、`app/src/main/java/com/stephen/autolyrics/data/LyricsDatabase.kt`
- Test: `app/src/androidTest/java/com/stephen/autolyrics/data/LyricsDaoTest.kt`

**Interfaces:**
- Consumes: 冇
- Produces:
  - `@Entity data class LyricsEntity(cacheKey, syncedLyrics: String?, fetchedAtMs: Long)` —— `syncedLyrics == null` 代表 negative cache
  - `interface LyricsDao { suspend fun get(cacheKey): LyricsEntity?; suspend fun put(entity); suspend fun deleteExpiredNegatives(cutoffMs: Long) }`
  - `abstract class LyricsDatabase : RoomDatabase()`

- [ ] **Step 1: 寫失敗測試（instrumented —— Room 要真 SQLite）**

```kotlin
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
```

- [ ] **Step 2: 行測試確認 fail**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*LyricsDaoTest*'`
Expected: FAIL —— compile error，`LyricsDatabase` unresolved（要有模擬器／實機連住）

- [ ] **Step 3: 寫實作**

`LyricsEntity.kt`:

```kotlin
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
```

`LyricsDatabase.kt`:

```kotlin
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
```

- [ ] **Step 4: 行測試確認通過**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*LyricsDaoTest*'`
Expected: PASS（5 個 test）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/data/ app/src/androidTest/
git commit -m "feat: add Room lyrics cache"
```

---

## Task 8: LyricsRepository（memory → DB → network）

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/data/LyricsRepository.kt`、`app/src/main/java/com/stephen/autolyrics/data/QueryLog.kt`
- Test: `app/src/test/java/com/stephen/autolyrics/data/LyricsRepositoryTest.kt`

**Interfaces:**
- Consumes: `LyricsSource`、`LyricsResult`、`LyricsOrigin`（Task 6）、`LyricsDao`、`LyricsEntity`（Task 7）、`TrackKey`（Task 4）、`LrcParser`（Task 2）
- Produces:
  - `class LyricsRepository(dao, networkSource, log, nowMs: () -> Long)`
  - `suspend fun LyricsRepository.lookup(key: TrackKey): LyricsResult`
  - `class QueryLog { fun record(entry); val entries: List<QueryLogEntry> }`
  - `data class QueryLogEntry(title, artist, queryUsed, outcome, origin, atMs)`

- [ ] **Step 1: 寫失敗測試**

```kotlin
package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.TrackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    private val key = TrackKey("Song", "Artist")

    /** 記住 lookup 次數嘅假 network source。 */
    private class FakeSource(var result: LyricsResult) : LyricsSource {
        var calls = 0
        override suspend fun lookup(key: TrackKey): LyricsResult {
            calls++
            return result
        }
    }

    /** In-memory 假 DAO。 */
    private class FakeDao : LyricsDao {
        val rows = mutableMapOf<String, LyricsEntity>()
        var getCalls = 0
        override suspend fun get(cacheKey: String): LyricsEntity? {
            getCalls++
            return rows[cacheKey]
        }
        override suspend fun put(entity: LyricsEntity) { rows[entity.cacheKey] = entity }
        override suspend fun deleteExpiredNegatives(cutoffMs: Long) {
            rows.entries.removeAll { it.value.syncedLyrics == null && it.value.fetchedAtMs < cutoffMs }
        }
    }

    private fun repo(
        dao: FakeDao = FakeDao(),
        source: FakeSource = FakeSource(LyricsResult.NotFound),
        now: Long = 100_000,
    ) = LyricsRepository(dao, source, QueryLog(), nowMs = { now })

    private val foundResult = LyricsResult.Found(
        com.stephen.autolyrics.lyrics.LrcParser.parse("[00:01.00]line one"),
        LyricsOrigin.NETWORK,
    )

    @Test
    fun `fetches from network on a cold cache and stores the result`() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        val result = r.lookup(key)

        assertTrue(result is LyricsResult.Found)
        assertEquals(1, source.calls)
        assertEquals("[00:01.00]line one", dao.rows[key.cacheKey()]?.syncedLyrics)
    }

    @Test
    fun `memory hit does not touch the dao`() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        r.lookup(key)
        val daoCallsAfterFirst = dao.getCalls
        val result = r.lookup(key)

        assertTrue(result is LyricsResult.Found)
        assertEquals(LyricsOrigin.MEMORY, (result as LyricsResult.Found).origin)
        assertEquals(daoCallsAfterFirst, dao.getCalls)  // 冇再問 DB
        assertEquals(1, source.calls)                   // 冇再出網絡
    }

    @Test
    fun `database hit does not hit the network`() = runBlocking {
        val dao = FakeDao().apply {
            rows[key.cacheKey()] = LyricsEntity(key.cacheKey(), "[00:01.00]line one", 90_000)
        }
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        val result = r.lookup(key)

        assertEquals(LyricsOrigin.DATABASE, (result as LyricsResult.Found).origin)
        assertEquals(0, source.calls)
    }

    @Test
    fun `stores a negative cache entry on NotFound`() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(LyricsResult.NotFound)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        r.lookup(key)

        assertTrue(dao.rows.containsKey(key.cacheKey()))
        assertEquals(null, dao.rows[key.cacheKey()]?.syncedLyrics)
    }

    @Test
    fun `unexpired negative cache is not re-queried`() = runBlocking {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        val dao = FakeDao().apply {
            rows[key.cacheKey()] = LyricsEntity(key.cacheKey(), null, fetchedAtMs = 1_000)
        }
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 1_000 + sevenDays - 1 })

        assertEquals(LyricsResult.NotFound, r.lookup(key))
        assertEquals(0, source.calls)
    }

    @Test
    fun `expired negative cache is re-queried`() = runBlocking {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        val dao = FakeDao().apply {
            rows[key.cacheKey()] = LyricsEntity(key.cacheKey(), null, fetchedAtMs = 1_000)
        }
        val source = FakeSource(foundResult)
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 1_000 + sevenDays + 1 })

        assertTrue(r.lookup(key) is LyricsResult.Found)
        assertEquals(1, source.calls)
    }

    @Test
    fun `network Error does not write a negative cache entry`() = runBlocking {
        // 關鍵：503 / timeout 唔可以封住首歌 7 日
        val dao = FakeDao()
        val source = FakeSource(LyricsResult.Error("HTTP 503"))
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        val result = r.lookup(key)

        assertTrue(result is LyricsResult.Error)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `network Error is not cached in memory either`() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(LyricsResult.Error("HTTP 503"))
        val r = LyricsRepository(dao, source, QueryLog(), nowMs = { 100_000 })

        r.lookup(key)
        r.lookup(key)

        assertEquals(2, source.calls)  // 兩次都要重試
    }

    @Test
    fun `queries with the normalized title`() = runBlocking {
        val source = object : LyricsSource {
            var seen: TrackKey? = null
            override suspend fun lookup(key: TrackKey): LyricsResult {
                seen = key
                return LyricsResult.NotFound
            }
        }
        val r = LyricsRepository(FakeDao(), source, QueryLog(), nowMs = { 100_000 })

        r.lookup(TrackKey("Song Name (Remastered 2011)", "Artist"))

        assertEquals("song name", source.seen?.title)
    }

    @Test
    fun `records each lookup in the query log`() = runBlocking {
        val log = QueryLog()
        val r = LyricsRepository(FakeDao(), FakeSource(LyricsResult.NotFound), log, nowMs = { 100_000 })

        r.lookup(TrackKey("Song Name (Live)", "Artist"))

        assertEquals(1, log.entries.size)
        assertEquals("Song Name (Live)", log.entries[0].title)
        assertEquals("song name", log.entries[0].queryUsed)
        assertEquals("NotFound", log.entries[0].outcome)
    }

    @Test
    fun `query log keeps only the most recent entries`() = runBlocking {
        val log = QueryLog(capacity = 3)
        val r = LyricsRepository(FakeDao(), FakeSource(LyricsResult.NotFound), log, nowMs = { 100_000 })

        repeat(5) { i -> r.lookup(TrackKey("Song $i", "Artist")) }

        assertEquals(3, log.entries.size)
        assertEquals("Song 4", log.entries.first().title)  // 最新排頭
    }
}
```

- [ ] **Step 2: 行測試確認 fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*LyricsRepositoryTest*'`
Expected: FAIL —— `LyricsRepository` / `QueryLog` unresolved

- [ ] **Step 3: 寫 `QueryLog.kt`**

```kotlin
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
```

- [ ] **Step 4: 寫 `LyricsRepository.kt`**

```kotlin
package com.stephen.autolyrics.data

import com.stephen.autolyrics.lyrics.LrcParser
import com.stephen.autolyrics.lyrics.TrackKey

/**
 * 查詢順序：memory → Room → network。
 *
 * Cache 規則：
 *  - Found    → 永久存 DB（歌詞唔會變）
 *  - NotFound → 存 negative cache，TTL 7 日
 *  - Error    → **唔存**（503 / timeout 唔可以封住首歌）
 */
class LyricsRepository(
    private val dao: LyricsDao,
    private val networkSource: LyricsSource,
    private val log: QueryLog,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val memory = mutableMapOf<String, LyricsResult>()

    suspend fun lookup(key: TrackKey): LyricsResult {
        val normalized = key.normalized()
        val cacheKey = key.cacheKey()

        val result = resolve(normalized, cacheKey)

        log.record(
            QueryLogEntry(
                title = key.title,
                artist = key.artist,
                queryUsed = normalized.title,
                outcome = when (result) {
                    is LyricsResult.Found -> "Found"
                    is LyricsResult.NotFound -> "NotFound"
                    is LyricsResult.Error -> "Error: ${result.reason}"
                },
                origin = (result as? LyricsResult.Found)?.origin,
                atMs = nowMs(),
            )
        )
        return result
    }

    private suspend fun resolve(normalized: TrackKey, cacheKey: String): LyricsResult {
        // 1. Memory
        memory[cacheKey]?.let { cached ->
            return when (cached) {
                is LyricsResult.Found -> cached.copy(origin = LyricsOrigin.MEMORY)
                else -> cached
            }
        }

        // 2. Room
        dao.get(cacheKey)?.let { row ->
            val lyrics = row.syncedLyrics
            if (lyrics != null) {
                val hit = LyricsResult.Found(LrcParser.parse(lyrics), LyricsOrigin.DATABASE)
                memory[cacheKey] = hit
                return hit
            }
            // negative entry —— 睇下過咗期未
            if (nowMs() - row.fetchedAtMs < NEGATIVE_TTL_MS) {
                memory[cacheKey] = LyricsResult.NotFound
                return LyricsResult.NotFound
            }
        }

        // 3. Network
        return when (val fresh = networkSource.lookup(normalized)) {
            is LyricsResult.Found -> {
                dao.put(LyricsEntity(cacheKey, rawOf(fresh), nowMs()))
                memory[cacheKey] = fresh
                fresh
            }
            is LyricsResult.NotFound -> {
                dao.put(LyricsEntity(cacheKey, null, nowMs()))
                memory[cacheKey] = LyricsResult.NotFound
                LyricsResult.NotFound
            }
            // 暫時性失敗：唔寫 DB、唔寫 memory，下次自然重試
            is LyricsResult.Error -> fresh
        }
    }

    /** 由已解析嘅歌詞砌返 LRC 文字存落 DB。 */
    private fun rawOf(found: LyricsResult.Found): String =
        found.lyrics.lines.joinToString("\n") { line ->
            val totalCs = line.timeMs / 10
            val minutes = totalCs / 6_000
            val seconds = (totalCs / 100) % 60
            val centis = totalCs % 100
            "[%02d:%02d.%02d]%s".format(minutes, seconds, centis, line.text)
        }

    private companion object {
        const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 日
    }
}
```

- [ ] **Step 5: 行測試確認通過**

Run: `./gradlew :app:testDebugUnitTest --tests '*LyricsRepositoryTest*'`
Expected: PASS（11 個 test）

- [ ] **Step 6: 行晒全部 JVM test**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（Task 2-8 全部）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/data/ app/src/test/java/com/stephen/autolyrics/data/
git commit -m "feat: add lyrics repository with layered cache"
```

---

## Task 9: MediaWatcher（NotificationListenerService）

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/media/MediaWatcher.kt`、`app/src/main/java/com/stephen/autolyrics/media/NotificationMediaWatcher.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `PlaybackState`（Task 5）
- Produces:
  - `interface MediaWatcher { val state: StateFlow<PlaybackState?> }`
  - `class NotificationMediaWatcher : NotificationListenerService(), MediaWatcher`
  - `object ActiveMediaWatcher { val state: StateFlow<PlaybackState?> }` —— 畀 UI 訂閱嘅單例入口

> **呢個 task 冇 unit test。** NotificationListenerService 要系統綁定先行到，Robolectric 模擬唔到 `MediaSessionManager.getActiveSessions()` 嘅行為。驗證靠 Step 5 嘅手動測試 + Task 10 個 log 畫面。

- [ ] **Step 1: 寫 `MediaWatcher.kt`**

```kotlin
package com.stephen.autolyrics.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface MediaWatcher {
    val state: StateFlow<PlaybackState?>
}

/**
 * 畀兩個 UI 訂閱嘅單例入口。NotificationMediaWatcher 係唯一嘅寫入者。
 */
object ActiveMediaWatcher : MediaWatcher {
    internal val mutableState = MutableStateFlow<PlaybackState?>(null)
    override val state: StateFlow<PlaybackState?> = mutableState
}
```

- [ ] **Step 2: 寫 `NotificationMediaWatcher.kt`**

```kotlin
package com.stephen.autolyrics.media

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 唯一掂 NotificationListener 嘅檔案。
 *
 * ─── 三個自我約束（README 有講，呢度係實際執行嘅地方）───────────────
 *
 * 1. onNotificationPosted / onNotificationRemoved **只**用嚟觸發 session 重新掃描。
 *    callback 入面唔讀 StatusBarNotification 嘅任何內容欄位 ——
 *    唔碰 sbn.notification.extras、tickerText、actions。參數名寫成 `unused` 就係為咗
 *    喺 code 層面講清楚：我哋唔睇通知內容。
 *
 * 2. 唔用 MediaController 嘅任何控制 API（play() / pause() / skipToNext() 等）。
 *    只讀 metadata 同 playbackState。
 *
 * 3. 唔記錄、唔上傳任何通知相關資料。
 *
 * ────────────────────────────────────────────────────────────
 */
class NotificationMediaWatcher : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindTo(controllers?.firstOrNull())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: AndroidPlaybackState?) = publish()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, NotificationMediaWatcher::class.java)
        sessionManager =
            (getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager).also { manager ->
                manager.addOnActiveSessionsChangedListener(sessionsListener, component)
                bindTo(manager.getActiveSessions(component).firstOrNull())
            }
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(controllerCallback)
        controller = null
        ActiveMediaWatcher.mutableState.value = null
        super.onListenerDisconnected()
    }

    // 約束 1：只 trigger 重掃，唔讀通知內容
    override fun onNotificationPosted(unused: StatusBarNotification?) = refreshSessions()
    override fun onNotificationRemoved(unused: StatusBarNotification?) = refreshSessions()

    private fun refreshSessions() {
        val manager = sessionManager ?: return
        val component = ComponentName(this, NotificationMediaWatcher::class.java)
        bindTo(manager.getActiveSessions(component).firstOrNull())
    }

    private fun bindTo(next: MediaController?) {
        if (next?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback)
        publish()
    }

    /** 約束 2：只讀 metadata 同 playbackState，唔掂任何控制 API。 */
    private fun publish() {
        val active = controller
        val metadata = active?.metadata
        val playback = active?.playbackState

        if (metadata == null || playback == null) {
            ActiveMediaWatcher.mutableState.value = null
            return
        }

        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()

        if (title.isBlank() || artist.isBlank()) {
            ActiveMediaWatcher.mutableState.value = null
            return
        }

        ActiveMediaWatcher.mutableState.value = PlaybackState(
            title = title,
            artist = artist,
            album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            positionMs = playback.position,
            // getLastPositionUpdateTime() 係 elapsedRealtime 基準，同 PositionEstimator 對得上
            positionUpdateTimeMs = playback.lastPositionUpdateTime
                .takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            playbackSpeed = playback.playbackSpeed,
            isPlaying = playback.state == AndroidPlaybackState.STATE_PLAYING,
        )
    }
}
```

- [ ] **Step 3: 喺 manifest 註冊 service**

喺 `<application>` 入面加：

```xml
        <service
            android:name=".media.NotificationMediaWatcher"
            android:exported="true"
            android:label="Auto Lyrics"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
```

- [ ] **Step 4: 確認編譯通過**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/media/ app/src/main/AndroidManifest.xml
git commit -m "feat: add notification-based media watcher"
```

> **手動驗證推遲到 Task 10 之後** —— 要等 log 畫面出咗先睇得到 `PlaybackState` 有冇嘢。

---

## Task 10: 手機 UI（權限引導 + log 畫面）

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/phone/MainActivity.kt`、`app/src/main/java/com/stephen/autolyrics/AutoLyricsApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ActiveMediaWatcher`（Task 9）、`QueryLog` / `QueryLogEntry`（Task 8）、`LyricsRepository`（Task 8）、`LrclibSource`（Task 6）、`LyricsDatabase`（Task 7）
- Produces: `object AppGraph { fun repository(context): LyricsRepository; val queryLog: QueryLog }` —— 畀 car UI 共用同一個 repository 實例

- [ ] **Step 1: 寫 `AutoLyricsApp.kt`（共用 dependency graph）**

```kotlin
package com.stephen.autolyrics

import android.content.Context
import com.stephen.autolyrics.data.LrclibSource
import com.stephen.autolyrics.data.LyricsDatabase
import com.stephen.autolyrics.data.LyricsRepository
import com.stephen.autolyrics.data.QueryLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 手動 DI —— 車機同手機兩邊共用同一個 repository / log 實例。
 */
object AppGraph {

    val queryLog = QueryLog()

    // UA 寫真實資料：app 名 + 版本 + 本 repo。LRCLIB 要求 UA 可識別。
    private const val USER_AGENT =
        "AutoLyrics/${BuildConfig.VERSION_NAME} (https://github.com/stephenyctsedev/auto-lyrics)"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var repo: LyricsRepository? = null

    fun repository(context: Context): LyricsRepository = repo ?: synchronized(this) {
        repo ?: LyricsRepository(
            dao = LyricsDatabase.get(context).lyricsDao(),
            networkSource = LrclibSource(client = httpClient, userAgent = USER_AGENT),
            log = queryLog,
        ).also { repo = it }
    }
}
```

要用 `BuildConfig.VERSION_NAME`，喺 `app/build.gradle.kts` 嘅 `android { }` 加：

```kotlin
    buildFeatures { compose = true; buildConfig = true }
```

- [ ] **Step 2: 寫 `MainActivity.kt`**

```kotlin
package com.stephen.autolyrics.phone

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stephen.autolyrics.AppGraph
import com.stephen.autolyrics.media.ActiveMediaWatcher
import com.stephen.autolyrics.media.NotificationMediaWatcher

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        isListenerEnabled = ::isListenerEnabled,
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                    )
                }
            }
        }
    }

    /** 檢查用家喺系統設定開咗 notification access 未。 */
    private fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val component = ComponentName(this, NotificationMediaWatcher::class.java)
        return enabled?.split(':')?.any {
            ComponentName.unflattenFromString(it) == component
        } == true
    }
}

@Composable
private fun HomeScreen(
    isListenerEnabled: () -> Boolean,
    onOpenSettings: () -> Unit,
) {
    var granted by remember { mutableStateOf(isListenerEnabled()) }
    val nowPlaying by ActiveMediaWatcher.state.collectAsStateWithLifecycle()

    // 由設定返嚟嗰陣重新檢查
    LaunchedEffect(Unit) { granted = isListenerEnabled() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        Text("Auto Lyrics", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (!granted) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("需要通知存取權限", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "呢個 app 用通知存取權限嚟讀系統嘅媒體播放狀態（歌名、歌手、播放位置）—— " +
                        "Android 冇其他途徑畀第三方 app 攞呢啲資料。\n\n" +
                        "本 app 唔會讀取任何通知內容，亦唔會上傳任何資料。"
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenSettings) { Text("開啟設定") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("而家播緊", style = MaterialTheme.typography.titleMedium)
        Text(
            nowPlaying?.let { "${it.title} — ${it.artist}" } ?: "（冇偵測到播放中嘅媒體）",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(24.dp))
        Text("查詢紀錄", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        val entries = AppGraph.queryLog.entries
        if (entries.isEmpty()) {
            Text("（未有查詢）", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(entries) { entry ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("${entry.title} — ${entry.artist}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "query: \"${entry.queryUsed}\" → ${entry.outcome}" +
                                (entry.origin?.let { " [$it]" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
```

`AppGraph.queryLog.entries` 係普通 list，唔會自動觸發 recomposition。第一版接受咁 —— 每次返返個畫面就會重讀。

- [ ] **Step 3: 喺 manifest 註冊 activity**

喺 `<application>` 入面加：

```xml
        <activity
            android:name=".phone.MainActivity"
            android:exported="true"
            android:label="Auto Lyrics">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

同時加 lifecycle-compose 依賴到 `app/build.gradle.kts`（`collectAsStateWithLifecycle` 要）：

```kotlin
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
```

順手加去 version catalog：

```toml
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
```

- [ ] **Step 4: 裝落實機／模擬器**

Run: `./gradlew :app:installDebug`
Expected: 裝到，開得到 app

- [ ] **Step 5: 手動驗證 MediaWatcher（Task 9 嘅驗證喺呢度做）**

1. 開 app → 撳「開啟設定」→ 喺系統設定開 Auto Lyrics 嘅通知存取
2. 返 app，開 Spotify 播歌 → 「而家播緊」應該顯示歌名同歌手
3. 換一首歌 → 顯示跟住變
4. 用 YouTube Music 再試一次
5. 用本地播放器再試一次
6. 撳暫停 → 歌名應該仲喺度（`isPlaying` 變 false 但 state 唔係 null）

如果「而家播緊」一直係空白，睇 `adb logcat` 有冇 `NotificationMediaWatcher` 相關 exception。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/ app/src/main/AndroidManifest.xml app/build.gradle.kts gradle/libs.versions.toml
git commit -m "feat: add phone UI with permission onboarding and query log"
```

---

## Task 11: Android Auto UI

**Files:**
- Create: `app/src/main/java/com/stephen/autolyrics/car/LyricsCarAppService.kt`、`app/src/main/java/com/stephen/autolyrics/car/LyricsSession.kt`、`app/src/main/java/com/stephen/autolyrics/car/CarLyricsScreen.kt`、`app/src/main/res/xml/automotive_app_desc.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ActiveMediaWatcher`（Task 9）、`AppGraph.repository()`（Task 10）、`LyricsSync`（Task 3）、`PositionEstimator`（Task 5）、`TrackKey`（Task 4）
- Produces: 冇（終端 UI）

**設計約束（來自研究）：** Android Auto host 會 **coalesce**（合併）短時間內嘅多次 `invalidate()` —— 唔係拒絕，係只顯示最後嗰個 template。所以「行號冇變就唔 invalidate」係啱嘅策略。另外 host 對一個 task 最多 5 個 template，所以呢度**只用一個 screen 原地刷新**，唔做 screen 堆疊。

- [ ] **Step 1: 建立 `automotive_app_desc.xml`**

`app/src/main/res/xml/automotive_app_desc.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="template" />
</automotiveApp>
```

- [ ] **Step 2: 寫 `CarLyricsScreen.kt`**

```kotlin
package com.stephen.autolyrics.car

import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.CarText
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.stephen.autolyrics.AppGraph
import com.stephen.autolyrics.data.LyricsResult
import com.stephen.autolyrics.lyrics.LyricsSync
import com.stephen.autolyrics.lyrics.ParsedLyrics
import com.stephen.autolyrics.lyrics.TrackKey
import com.stephen.autolyrics.media.ActiveMediaWatcher
import com.stephen.autolyrics.media.PlaybackState
import com.stephen.autolyrics.media.PositionEstimator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 單一 screen，原地刷新。
 *
 * Refresh 策略：250ms tick 推算位置，但**只有當前行號變咗**先 invalidate()。
 * Android Auto host 會合併短時間內嘅多次更新，所以減少 invalidate 次數
 * 直接改善顯示流暢度。快歌一句 2 秒 → 每 2 秒先 invalidate 一次。
 */
class CarLyricsScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var nowPlaying: PlaybackState? = null
    private var lyrics: ParsedLyrics? = null
    private var currentLine: Int? = null
    private var lookupJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        // 訂閱播放狀態：換歌就重新查歌詞
        lifecycleScope.launch {
            ActiveMediaWatcher.state.collectLatest { state ->
                val changed = state?.title != nowPlaying?.title ||
                    state?.artist != nowPlaying?.artist
                nowPlaying = state

                if (changed) {
                    lyrics = null
                    currentLine = null
                    invalidate()
                    if (state != null) startLookup(state)
                }
            }
        }

        // 250ms ticker：推算位置，行號變咗先重畫
        lifecycleScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val state = nowPlaying ?: continue
                val parsed = lyrics ?: continue
                val position = PositionEstimator.estimate(state, SystemClock.elapsedRealtime())
                val line = LyricsSync.currentLineIndex(parsed, position)
                if (line != currentLine) {
                    currentLine = line
                    invalidate()
                }
            }
        }
    }

    private fun startLookup(state: PlaybackState) {
        lookupJob?.cancel()
        lookupJob = lifecycleScope.launch {
            val result = AppGraph.repository(carContext)
                .lookup(TrackKey(state.title, state.artist))
            // 查完之後首歌可能已經換咗
            if (state.title != nowPlaying?.title) return@launch
            lyrics = (result as? LyricsResult.Found)?.lyrics
            currentLine = null
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        val state = nowPlaying
        val parsed = lyrics

        if (state == null) {
            pane.addRow(Row.Builder().setTitle("冇偵測到播放中嘅音樂").build())
        } else if (parsed == null || parsed.isEmpty) {
            // 靜靜降級：搵唔到 / 未查完 / 網絡唔通，一律顯示歌名歌手，唔出 error
            pane.addRow(
                Row.Builder()
                    .setTitle(CarText.create(state.title))
                    .addText(CarText.create(state.artist))
                    .build()
            )
        } else {
            visibleWindow(parsed).forEach { (index, text) ->
                val display = if (text.isBlank()) " " else text
                pane.addRow(
                    Row.Builder()
                        .setTitle(CarText.create(
                            if (index == currentLine) "▶ $display" else display
                        ))
                        .build()
                )
            }
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle(nowPlaying?.title ?: "Auto Lyrics")
            .build()
    }

    /** 當前句 + 前一句 + 後兩句。 */
    private fun visibleWindow(parsed: ParsedLyrics): List<Pair<Int, String>> {
        val lines = parsed.lines
        val current = currentLine ?: 0
        val start = (current - 1).coerceAtLeast(0)
        val end = (start + WINDOW_SIZE).coerceAtMost(lines.size)
        return (start until end).map { it to lines[it].text }
    }

    private companion object {
        const val TICK_MS = 250L
        const val WINDOW_SIZE = 4  // PaneTemplate 有行數上限，保守取 4
    }
}
```

- [ ] **Step 3: 寫 `LyricsSession.kt` 同 `LyricsCarAppService.kt`**

`LyricsSession.kt`:

```kotlin
package com.stephen.autolyrics.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class LyricsSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarLyricsScreen(carContext)
}
```

`LyricsCarAppService.kt`:

```kotlin
package com.stephen.autolyrics.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class LyricsCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = LyricsSession()
}
```

- [ ] **Step 4: 更新 manifest**

喺 `<application>` 入面加：

```xml
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:value="@xml/automotive_app_desc" />

        <service
            android:name=".car.LyricsCarAppService"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService" />
                <category android:name="androidx.car.app.category.POI" />
            </intent-filter>
        </service>
```

> `category.POI` 係 template app 之中最貼近「顯示資訊」嘅 category。因為呢個 app 唔上 Play Store（見 spec 非目標），category 只影響 host 點分類，唔影響 sideload 運作。

- [ ] **Step 5: Build 同裝**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 用 Desktop Head Unit 驗證（即係原 spec 階段 0 個 spike，而家喺真 code 上做）**

1. 手機開 developer mode → Android Auto → 開 "Unknown sources" 同 "Start head unit server"
2. 電腦行 `~/Library/Android/sdk/extras/google/auto/desktop-head-unit`（Windows：`%LOCALAPPDATA%\Android\Sdk\extras\google\auto\desktop-head-unit.exe`）
3. `adb forward tcp:5277 tcp:5277`
4. 播歌，喺 DHU 打開 Auto Lyrics

**要答嘅問題（原 spec 階段 0）：**
- 換句更新流唔流暢？有冇明顯跳格？
- `PaneTemplate` 實際顯示到幾多行？（`WINDOW_SIZE = 4` 啱唔啱，要唔要改）
- 快歌（一句 < 2 秒）會唔會被 host 壓住？

**如果 throttling 太嚴重：** 唔好硬做。返去 spec 重新傾顯示策略（例如改成兩句一組更新、或者改用 `ListTemplate`）。呢個係設計時已經標明嘅風險。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/stephen/autolyrics/car/ app/src/main/res/xml/ app/src/main/AndroidManifest.xml
git commit -m "feat: add Android Auto lyrics screen"
```

---

## Task 12: Release CI + README

**Files:**
- Create: `.github/workflows/release.yml`、`README.md`、`app/proguard-rules.pro`

**Interfaces:**
- Consumes: Task 1 嘅 signing config
- Produces: tag push → 簽好嘅 release APK + SHA-256 checksum + provenance

- [ ] **Step 1: 建立 `app/proguard-rules.pro`**

```proguard
# R8 只做 shrink / minify —— 唔做 string encryption 嗰類令人懷疑嘅嘢。

# Car App Library 靠 reflection 攞 template，要保住
-keep class androidx.car.app.** { *; }

# Room 生成嘅 class
-keep class * extends androidx.room.RoomDatabase { *; }

# NotificationListenerService 由系統綁定，唔可以改名
-keep class com.stephen.autolyrics.media.NotificationMediaWatcher { *; }
-keep class com.stephen.autolyrics.car.LyricsCarAppService { *; }
```

- [ ] **Step 2: 生成 release keystore（本機做，唔入 repo）**

```bash
keytool -genkeypair -v \
  -keystore ~/.android/keystores/autolyrics-release.jks \
  -alias autolyrics \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Auto Lyrics, O=Personal, C=HK"
```

跟住喺 `local.properties` 加（呢個檔已經喺 `.gitignore`）：

```properties
signing.storeFile=/Users/stephen/.android/keystores/autolyrics-release.jks
signing.storePassword=<你設嘅密碼>
signing.keyAlias=autolyrics
signing.keyPassword=<你設嘅密碼>
```

記低 fingerprint（README 要寫）：

```bash
keytool -list -v -keystore ~/.android/keystores/autolyrics-release.jks -alias autolyrics | grep SHA256
```

- [ ] **Step 3: 設定 GitHub Secrets**

```bash
base64 -w0 ~/.android/keystores/autolyrics-release.jks > keystore.b64
gh secret set SIGNING_KEYSTORE_B64 < keystore.b64
gh secret set SIGNING_STORE_PASSWORD
gh secret set SIGNING_KEY_ALIAS
gh secret set SIGNING_KEY_PASSWORD
rm keystore.b64   # 即刻刪走
```

- [ ] **Step 4: 建立 `.github/workflows/release.yml`**

```yaml
name: release

on:
  push:
    tags: ['v*']

# 預設乜都唔畀；下面個 job 先開佢實際需要嘅權限
permissions: {}

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: write        # 發佈 release 要
      id-token: write        # attestation 要
      attestations: write    # attestation 要
    steps:
      # actions/checkout v4.2.2
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683

      # actions/setup-java v4.5.0
      - uses: actions/setup-java@8df1039502a15bceb9433410b1a100fbe190c53b
        with:
          distribution: temurin
          java-version: '17'

      # gradle/actions/wrapper-validation v4.2.1
      - uses: gradle/actions/wrapper-validation@cc4fc85e6b35bafd578d5ffbc76a5518407e1af0

      # gradle/actions/setup-gradle v4.2.1
      - uses: gradle/actions/setup-gradle@cc4fc85e6b35bafd578d5ffbc76a5518407e1af0
        with:
          cache-read-only: true

      - name: Decode keystore
        env:
          KEYSTORE_B64: ${{ secrets.SIGNING_KEYSTORE_B64 }}
        run: |
          mkdir -p "$RUNNER_TEMP/signing"
          echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/signing/release.jks"

      - name: Build signed release APK
        env:
          SIGNING_STORE_FILE: ${{ runner.temp }}/signing/release.jks
          SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
        run: ./gradlew assembleRelease --no-daemon

      - name: Verify the APK is signed with the release key
        run: |
          APK=app/build/outputs/apk/release/app-release.apk
          test -f "$APK" || { echo "release APK missing — signing config likely not applied"; exit 1; }
          "$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --print-certs "$APK"

      - name: Generate checksum
        run: |
          cd app/build/outputs/apk/release
          sha256sum app-release.apk > app-release.apk.sha256
          cat app-release.apk.sha256

      # actions/attest-build-provenance v2.1.0
      - uses: actions/attest-build-provenance@520d128f165991a6c774bcb264f323e3d70747f4
        with:
          subject-path: app/build/outputs/apk/release/app-release.apk

      # softprops/action-gh-release v2.2.1
      - uses: softprops/action-gh-release@c95fe1489396fe8a9eb87c0abf8aa5b2ef267fda
        with:
          files: |
            app/build/outputs/apk/release/app-release.apk
            app/build/outputs/apk/release/app-release.apk.sha256

      - name: Remove keystore
        if: always()
        run: rm -rf "$RUNNER_TEMP/signing"
```

> **注意：** 全部 action SHA 要自己核對過先用（例如 `gh api repos/softprops/action-gh-release/git/ref/tags/v2.2.1`）。

- [ ] **Step 5: 寫 `README.md`**

````markdown
# Auto Lyrics

喺 Android Auto 車機畫面顯示當前播放歌曲嘅同步歌詞。

## 點解需要「通知存取」權限

呢個 app 要用 `BIND_NOTIFICATION_LISTENER_SERVICE`（系統設定入面叫「通知存取」）。

**點解：** Android 冇提供任何官方 API 畀第三方 app 直接讀「而家播緊咩歌、播到第幾秒」。
唯一途徑係經 `MediaSessionManager.getActiveSessions()`，而呢個 method 要求 caller 係一個
已授權嘅 NotificationListenerService。

**實際用嚟做咩：** 只係攞 `MediaSessionManager`，再由佢讀歌名、歌手、播放位置。

**三個自我約束**（實作喺 [`NotificationMediaWatcher.kt`](app/src/main/java/com/stephen/autolyrics/media/NotificationMediaWatcher.kt)）：

1. `onNotificationPosted` / `onNotificationRemoved` **只**用嚟觸發 media session 重新掃描。
   callback 入面唔讀 `StatusBarNotification` 嘅任何內容欄位 —— 參數名寫咗做 `unused`，
   你可以自己 grep 確認冇碰過 `sbn.notification.extras`、`tickerText` 或 `actions`。
2. 唔用 `MediaController` 嘅任何控制 API（`play()` / `pause()` / `skipToNext()`）。只讀
   `metadata` 同 `playbackState`。
3. 唔記錄、唔上傳任何通知相關資料。

自己驗證：

```bash
grep -rn "extras\|tickerText\|\.actions\|skipToNext\|\.play()\|\.pause()" app/src/main/java/
```

## 權限清單

| 權限 | 用途 |
|---|---|
| `INTERNET` | 向 LRCLIB 查歌詞（只有 GET） |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 攞 `MediaSessionManager`（見上面） |

**刻意唔要嘅：** `MEDIA_CONTENT_CONTROL`（signature-level，普通 app 攞唔到，宣告咗都冇用）、
`RECEIVE_BOOT_COMPLETED`（系統會自動拉返 NotificationListenerService）、`RECORD_AUDIO`。

## 網絡行為

- 唯一外連目標：`https://lrclib.net/api/get`
- **只有 GET，零 POST，零 telemetry / analytics**
- 只送**歌名同歌手**。冇裝置 ID、冇帳號、冇位置
- User-Agent 寫真實資料（app 名 + 版本 + 本 repo URL），符合 LRCLIB 要求
- 唔做 cert pinning（LRCLIB 用 Let's Encrypt，證書會轉，pin 咗會令 app 突然壞）

自己驗證所有外連 URL：

```bash
grep -rnoE 'https?://[^"'"'"' )]+' app/src/main/java/
```

## Build

### 本地

1. 生成 release keystore（**唔好** commit）：

   ```bash
   keytool -genkeypair -v \
     -keystore ~/.android/keystores/autolyrics-release.jks \
     -alias autolyrics -keyalg RSA -keysize 4096 -validity 10000
   ```

2. `local.properties` 加（呢個檔喺 `.gitignore` 入面）：

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

冇配置 keystore 嘅話，`assembleRelease` 會出 unsigned APK —— **唔會**靜靜跌返用 debug key 簽。

### CI

Push 一個 `v*` tag 就會 build 簽好嘅 release APK，附 SHA-256 checksum 同 build provenance。
Keystore 由 `SIGNING_KEYSTORE_B64` secret 解碼落 runner 臨時目錄，build 完即刪。

## 驗證你手上嘅 APK

```bash
apksigner verify --print-certs app-release.apk
sha256sum -c app-release.apk.sha256
```

Release key SHA-256 fingerprint：

```
<喺 Task 12 Step 2 攞到之後填返落嚟>
```

## 已知限制

- **上唔到 Google Play。** Android Auto 對第三方 app 有 category 白名單，「歌詞顯示」唔屬於任何
  現有 category。呢個 app 係自用 sideload。
- **只支援有時間戳嘅歌詞。** LRCLIB 只有純文字歌詞嘅歌會當搵唔到處理（冇時間戳跟唔到秒數，
  喺車機顯示一大段唔會郁嘅文字反而係視覺干擾）。
- **歌名對唔上會 miss。** Metadata 嘅 `(Remastered 2011)` 之類後綴會做輕度正規化，但唔完美。
  手機 app 個查詢紀錄會顯示實際用咗咩 query，方便查。
````

- [ ] **Step 6: 本地驗證 release build**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL，`app/build/outputs/apk/release/app-release.apk` 存在

驗證簽名（確認用咗 release key，唔係 debug key）：

Run: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
Expected: 印出 `CN=Auto Lyrics`，SHA-256 同 Step 2 記低嗰個一樣

- [ ] **Step 7: Commit 同出第一個 release**

```bash
git add .github/workflows/release.yml README.md app/proguard-rules.pro
git commit -m "chore: add release pipeline and README"
git tag v0.1.0
git push origin main --tags
```

去 GitHub Actions 睇 release workflow 跑成功，然後由 Release 頁下載 APK，核對 checksum，裝落實機。

---

## 附錄：驗收清單

實作完晒之後逐項核對（對應 spec 嘅安全設計）：

- [ ] `grep -rn "storePassword\|keyPassword" app/build.gradle.kts` → 只有 `secret(...)` 呼叫，冇明文
- [ ] `git ls-files | grep -iE '\.(jks|p12|keystore)$'` → 冇輸出
- [ ] `grep -c "uses-permission" app/src/main/AndroidManifest.xml` → 1（只有 INTERNET；listener 權限係 service 嘅 `android:permission`）
- [ ] `grep -rn "MEDIA_CONTENT_CONTROL\|RECEIVE_BOOT_COMPLETED\|RECORD_AUDIO" app/src/` → 冇輸出
- [ ] `grep -rnoE 'https?://[^"'"'"' )]+' app/src/main/java/` → 只有 `lrclib.net` 同 README 入面嘅 repo URL
- [ ] `grep -rn "DexClassLoader\|WebView\|loadUrl\|Runtime.getRuntime\|\.exec(" app/src/main/java/` → 冇輸出
- [ ] `grep -rn "POST\|\.post(" app/src/main/java/` → 冇輸出
- [ ] `grep -rn "allowBackup" app/src/main/AndroidManifest.xml` → `false`
- [ ] `grep -rn "@v[0-9]" .github/workflows/` → 冇輸出（全部 pin SHA）
- [ ] `grep -rn "distributionSha256Sum" gradle/wrapper/gradle-wrapper.properties` → 有值
- [ ] `apksigner verify --print-certs` release APK → CN=Auto Lyrics，唔係 Android Debug
- [ ] `./gradlew test` → 全部通過
