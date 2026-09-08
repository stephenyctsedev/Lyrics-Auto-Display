package com.stephen.autolyrics.auto

import android.content.Context
import android.content.SharedPreferences

/**
 * 「車機一屏出得幾多行」呢個數。
 *
 * 點解要記低：MediaBrowserService 係單程路 —— `onLoadChildren()` 送幾多個
 * MediaItem 出去係我哋話事，但 host 實際畫咗幾多行、畫唔畫得晒，冇任何
 * callback 話返畀我哋知。所以只可以喺車機度**量**一次（開校準模式，數到
 * 最後一行嗰個號碼），再喺手機入返個數，之後就永遠啱。
 *
 * 用 SharedPreferences 唔用 Room：得兩個 scalar，而且 `LyricsBrowserService`
 * 同 `MainActivity` 喺同一個 process（manifest 冇 `android:process`），
 * 所以個 change listener 兩邊都收到 —— 停低車喺手機撳個掣，車機即刻跟住變，
 * 唔使拔線重連。
 */
class CarRowSettings(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 校準出嚟嘅行數。null = 未校準過，跟 [BrowseRows.DESIRED_ROWS]。 */
    var rows: Int?
        get() = prefs.getInt(KEY_ROWS, 0).takeIf { it > 0 }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ROWS)
                else putInt(KEY_ROWS, value.coerceIn(BrowseRows.MIN_ROWS, BrowseRows.MAX_ROWS))
            }.apply()
        }

    /** 開住嘅時候車機出間尺，唔出歌詞。 */
    var calibrating: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CALIBRATING, value).apply()
        }

    /**
     * ⚠️ SharedPreferences 只 hold weak reference，caller 一定要自己揸實個
     * listener（擺喺 field，唔好擺 local 變數），否則 GC 之後就靜靜咁唔再收。
     */
    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val PREFS_NAME = "car_rows"
        const val KEY_ROWS = "rows"
        const val KEY_CALIBRATING = "calibrating"
    }
}
