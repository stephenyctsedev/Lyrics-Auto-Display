package com.stephen.autolyrics.phone

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.stephen.autolyrics.AppGraph
import com.stephen.autolyrics.auto.BrowseRows
import com.stephen.autolyrics.auto.CarRowSettings
import com.stephen.autolyrics.car.CarConnectionState
import com.stephen.autolyrics.car.CarLink
import com.stephen.autolyrics.lyrics.LyricsFeed
import com.stephen.autolyrics.lyrics.LyricsFeedState
import com.stephen.autolyrics.lyrics.LyricsStatus
import com.stephen.autolyrics.lyrics.message
import com.stephen.autolyrics.media.NotificationMediaWatcher
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var feed: LyricsFeed
    private lateinit var carRows: CarRowSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 手機畫面自己查歌詞，唔再淨係靠車機嗰邊發起。
        feed = LyricsFeed(applicationContext, lifecycleScope).also { it.start() }
        carRows = CarRowSettings(this)

        val carLink = MutableStateFlow(CarLink.DISCONNECTED)
        lifecycleScope.launch {
            // 只喺 STARTED 之後收 —— 畫面睇唔到嗰陣冇必要 query 個 provider。
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CarConnectionState.flow(applicationContext).collect { carLink.value = it }
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by feed.state.collectAsStateWithLifecycle()
                    val link by carLink.collectAsStateWithLifecycle()

                    // 呢兩個 state 就係 SharedPreferences 嘅鏡 —— 寫落 prefs 之後
                    // 順手更新，畫面即刻跟住郁；車機嗰邊靠 change listener 收到。
                    var rows by remember { mutableStateOf(carRows.rows) }
                    var calibrating by remember { mutableStateOf(carRows.calibrating) }

                    HomeScreen(
                        state = state,
                        carLink = link,
                        isListenerEnabled = ::isListenerEnabled,
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        carRows = rows,
                        calibrating = calibrating,
                        onCarRowsChange = { carRows.rows = it; rows = carRows.rows },
                        onCalibratingChange = { carRows.calibrating = it; calibrating = it },
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
    state: LyricsFeedState,
    carLink: CarLink,
    isListenerEnabled: () -> Boolean,
    onOpenSettings: () -> Unit,
    carRows: Int?,
    calibrating: Boolean,
    onCarRowsChange: (Int?) -> Unit,
    onCalibratingChange: (Boolean) -> Unit,
) {
    var granted by remember { mutableStateOf(isListenerEnabled()) }

    // 每次 ON_RESUME 都重新檢查 —— 用家由設定畫面撳「返回」嗰陣，Activity 通常
    // 只會 onPause → onResume（唔會 recreate），單次 LaunchedEffect(Unit) 唔會再執行，
    // 會令 granted 停留喺舊值，睇落好似個權限成功咗都仲叫緊你開。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = isListenerEnabled() }

    // 注意 modifier 次序：padding 要喺 verticalScroll 之前，唔係嘅話 padding
    // 會加落 scroll viewport 出面，內容會偏移到可視範圍以外。
    // fillMaxSize 亦都要喺最前，等個 Column 至少有成個窗口咁高。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Auto Lyrics",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            CarLinkBadge(carLink)
        }

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

        LyricsPane(state)

        Spacer(Modifier.height(24.dp))
        CarRowsCard(
            rows = carRows,
            calibrating = calibrating,
            onRowsChange = onCarRowsChange,
            onCalibratingChange = onCalibratingChange,
        )

        Spacer(Modifier.height(24.dp))
        QueryLogSection()
    }
}

/** 車機連接狀態指示燈。喺手機度睇得到而家通唔通，唔使靠估。 */
@Composable
private fun CarLinkBadge(link: CarLink) {
    val (label, color) = when (link) {
        CarLink.PROJECTION -> "Android Auto 已連接" to Color(0xFF2E7D32)
        CarLink.NATIVE -> "車機系統內運行" to Color(0xFF2E7D32)
        CarLink.DISCONNECTED -> "未連接車機" to Color(0xFF9E9E9E)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * 歌詞主體。手機同車機睇緊同一份 state，所以喺手機見到咩，
 * 連咗車就會同步顯示過去。
 */
@Composable
private fun LyricsPane(state: LyricsFeedState) {
    val playing = state.nowPlaying

    // 冇 early return：@Composable 嘅 body 畀 compiler 加咗 group 開始／結束
    // 標記，中途 return 會跳過 endReplaceableGroup()，令 slot table 錯位 ——
    // 結果係同一個 scope 之後啲 composable 靜靜咁唔畫出嚟，而且唔掟 exception。
    // 所以呢度一律用 if/else。
    if (playing == null) {
        Text("而家播緊", style = MaterialTheme.typography.titleMedium)
        Text(
            "（冇偵測到播放中嘅媒體）",
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(playing.title, style = MaterialTheme.typography.titleLarge)
        Text(
            playing.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        val lyrics = state.lyrics
        when {
            // 同車機講同一句 —— 手機見到「搵唔到」，車機就唔會靜靜咁淨係得個歌名。
            lyrics == null || lyrics.isEmpty -> Text(
                state.status.message(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.status == LyricsStatus.ERROR)
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                // 手機夠位，顯示多啲上文下理 —— 當前行前後各幾句。
                val current = state.currentLine ?: 0
                val from = (current - 2).coerceAtLeast(0)
                val to = (from + PHONE_WINDOW).coerceAtMost(lyrics.lines.size)
                Column {
                    for (i in from until to) {
                        val isCurrent = i == state.currentLine
                        Text(
                            lyrics.lines[i].text.ifBlank { "♪" },
                            style = if (isCurrent) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 車機一屏出幾多行 —— 連埋校準模式。
 *
 * 點解要人手校準：MediaBrowserService 冇 callback 話返畀我哋知 host 實際畫咗
 * 幾多行，送咗出去就冇下文。所以唯一問到真相嘅方法，就係喺車機出一把間尺，
 * 落車前望一眼數到邊行，入返個數落嚟。度一次，之後就啱。
 */
@Composable
private fun CarRowsCard(
    rows: Int?,
    calibrating: Boolean,
    onRowsChange: (Int?) -> Unit,
    onCalibratingChange: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("車機一屏行數", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "包含第一行歌曲資訊，其餘係歌詞。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onRowsChange(stepRows(rows, -1)) },
                    enabled = (rows ?: BrowseRows.DESIRED_ROWS) > BrowseRows.MIN_ROWS,
                ) { Text("−") }

                Text(
                    rows?.let { "$it 行" } ?: "自動（${BrowseRows.DESIRED_ROWS} 行）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OutlinedButton(
                    onClick = { onRowsChange(stepRows(rows, +1)) },
                    enabled = (rows ?: BrowseRows.DESIRED_ROWS) < BrowseRows.MAX_ROWS,
                ) { Text("+") }

                Spacer(Modifier.weight(1f))

                TextButton(onClick = { onRowsChange(null) }, enabled = rows != null) {
                    Text("回自動")
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("校準模式", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "開咗之後車機唔出歌詞，改為出「01、02、03…」一把間尺" +
                            "（最多 ${BrowseRows.MAX_ROWS} 行）。停低車望一眼，" +
                            "顯示到最後一行係邊個號碼，就將上面調做嗰個數 —— " +
                            "之後就顯示到幾多行出幾多行。校準完記住閂返。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = calibrating, onCheckedChange = onCalibratingChange)
            }

            if (calibrating) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "⚠️ 校準模式開緊 —— 車機而家出緊間尺，唔會顯示歌詞。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** null（自動）當 [BrowseRows.DESIRED_ROWS] 咁計，加減完夾返喺合法範圍。 */
private fun stepRows(rows: Int?, delta: Int): Int =
    ((rows ?: BrowseRows.DESIRED_ROWS) + delta)
        .coerceIn(BrowseRows.MIN_ROWS, BrowseRows.MAX_ROWS)

@Composable
private fun QueryLogSection() {
    Text("查詢紀錄", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    val entries = AppGraph.queryLog.entries
    if (entries.isEmpty()) {
        Text("（未有查詢）", style = MaterialTheme.typography.bodySmall)
    } else {
        Column {
            entries.take(MAX_LOG_ROWS).forEach { entry ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "${entry.title} — ${entry.artist}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
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

private const val PHONE_WINDOW = 7
private const val MAX_LOG_ROWS = 20
