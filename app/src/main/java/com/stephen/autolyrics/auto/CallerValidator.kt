package com.stephen.autolyrics.auto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * 邊個 app 可以 bind LyricsBrowserService。
 *
 * MediaBrowserService 一定要 exported=true 先畀 Android Auto bind 到，即係
 * 機上任何一個 app 都 bind 得到。冇檢查嘅話，第三方可以讀到你播緊咩、
 * 當前歌詞，所以喺 onGetRoot() 就要擋。
 *
 * 檢查兩樣嘢：package name 喺白名單，而且真係由 Google 簽（platform 簽名
 * 亦放行，因為部分車機/ROM 嘅 Android Auto 係預載組件）。淨係查 package name
 * 唔夠 —— 任何 app 都可以改名做 com.google.android.projection.gearhead。
 */
internal object CallerValidator {

    private const val GEARHEAD = "com.google.android.projection.gearhead"
    private const val ANDROID_AUTO_SIM = "com.google.android.autosimulator"
    private const val MEDIA_CENTER = "com.android.car.media"
    private const val CAR_SHELL = "com.android.car.carlauncher"

    private val ALLOWED_PACKAGES = setOf(
        GEARHEAD,          // Android Auto 本身
        ANDROID_AUTO_SIM,  // DHU / simulator
        MEDIA_CENTER,      // AAOS media host
        CAR_SHELL,         // AAOS launcher
    )

    fun isAllowed(context: Context, clientPackageName: String, clientUid: Int): Boolean {
        // 自己友：同一個 uid（我哋自己個 process / 自己 app 內部）一定放行。
        if (clientUid == Process.myUid()) return true

        if (clientPackageName !in ALLOWED_PACKAGES) return false

        // 個 uid 真係屬於嗰個 package 先算 —— 防止有 app 報一個唔屬於佢嘅名。
        val pm = context.packageManager
        val uidForPackage = try {
            pm.getPackageUid(clientPackageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        if (uidForPackage != clientUid) return false

        return isGoogleSigned(context, clientPackageName) ||
            isPlatformSigned(context, clientPackageName)
    }

    /** 同 Google Play Services 同一個簽名 —— Android Auto 係 Google 出嘅。 */
    private fun isGoogleSigned(context: Context, pkg: String): Boolean =
        signaturesMatch(context, pkg, "com.google.android.gms")

    /** 同 "android" 同一個簽名 —— 車機 ROM 預載嘅 media host 會係咁。 */
    private fun isPlatformSigned(context: Context, pkg: String): Boolean =
        signaturesMatch(context, pkg, "android")

    @Suppress("DEPRECATION")
    private fun signaturesMatch(context: Context, a: String, b: String): Boolean = try {
        // checkSignatures 比自己攞 signature 逐個比較穩陣：佢處理埋
        // multiple signers / signing rotation 嗰啲 case。
        context.packageManager.checkSignatures(a, b) == PackageManager.SIGNATURE_MATCH
    } catch (_: Exception) {
        false
    }
}
