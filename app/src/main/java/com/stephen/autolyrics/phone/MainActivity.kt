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
import com.stephen.autolyrics.car.CarConnectionState
import com.stephen.autolyrics.car.CarLink
import com.stephen.autolyrics.lyrics.LyricsFeed
import com.stephen.autolyrics.lyrics.LyricsFeedState
import com.stephen.autolyrics.media.NotificationMediaWatcher
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var feed: LyricsFeed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 手機畫面自己查歌詞，唔再淨係靠車機嗰邊發起。
        feed = LyricsFeed(applicationContext, lifecycleScope).also { it.start() }

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
                    HomeScreen(
                        state = state,
                        carLink = link,
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
    state: LyricsFeedState,
    carLink: CarLink,
    isListenerEnabled: () -> Boolean,
    onOpenSettings: () -> Unit,
) {
    var granted by remember { mutableStateOf(isListenerEnabled()) }

    // 每次 ON_RESUME 都重新檢查 —— 用家由設定畫面撳「返回」嗰陣，Activity 通常
    // 只會 onPause → onResume（唔會 recreate），單次 LaunchedEffect(Unit) 唔會再執行，
    // 會令 granted 停留喺舊值，睇落好似個權限成功咗都仲叫緊你開。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = isListenerEnabled() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
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

    if (playing == null) {
        Text("而家播緊", style = MaterialTheme.typography.titleMedium)
        Text(
            "（冇偵測到播放中嘅媒體）",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Text(playing.title, style = MaterialTheme.typography.titleLarge)
    Text(
        playing.artist,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))

    val lyrics = state.lyrics
    when {
        state.loading -> Text("搵緊歌詞…", style = MaterialTheme.typography.bodyMedium)

        lyrics == null || lyrics.isEmpty -> Text(
            "搵唔到呢首歌嘅同步歌詞。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
