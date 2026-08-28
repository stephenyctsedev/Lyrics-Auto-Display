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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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

    // 每次 ON_RESUME 都重新檢查 —— 用家由設定畫面撳「返回」嗰陣，Activity 通常
    // 只會 onPause → onResume（唔會 recreate），單次 LaunchedEffect(Unit) 唔會再執行，
    // 會令 granted 停留喺舊值，睇落好似個權限成功咗都仲叫緊你開。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = isListenerEnabled() }

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
