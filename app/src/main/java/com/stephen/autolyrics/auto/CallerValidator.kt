package com.stephen.autolyrics.auto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.security.MessageDigest

/**
 * 邊個 app 可以 bind LyricsBrowserService。
 *
 * MediaBrowserService 一定要 exported=true 先畀 Android Auto bind 到，即係
 * 機上任何一個 app 都 bind 得到。冇檢查嘅話，第三方可以讀到你播緊咩、
 * 當前歌詞，所以喺 onGetRoot() 就要擋。
 *
 * 檢查三樣：package name 喺白名單、個 uid 真係屬於嗰個 package、
 * 簽名 SHA-256 喺已知清單入面。淨係查 package name 唔夠 —— 任何 app
 * 都可以改名做 com.google.android.projection.gearhead。
 *
 * ⚠️ 唔可以用「同 com.google.android.gms 同簽名」嚟判斷：實測過
 * Android Auto（d2a5217f）同 Play Services（d1b21c60）**係兩個唔同嘅
 * 簽名**，咁樣寫會將真嘅 Android Auto 都拒之門外，車機會顯示
 * "Auto Lyrics doesn't seem to be working at the moment."
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

    /**
     * Google 用嚟簽 Android Auto / 車機組件嘅憑證 SHA-256。
     *
     * 第一個係 release key，第二個係 Google 內部 debug key（部分 ROM 上面
     * 預載嘅版本會用呢個簽）。同 Google 官方 media sample 嘅 PackageValidator
     * 用同一組值。
     */
    private val GOOGLE_SIGNATURES = setOf(
        "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053",
        "19:75:b2:f1:71:77:bc:89:a5:df:f3:1f:9e:64:a6:ca:e2:81:a5:3d:c1:d1:d5:9b:1d:14:7f:e1:c8:2a:fa:00"
            .replace(":", "").lowercase(),
    )

    fun isAllowed(context: Context, clientPackageName: String, clientUid: Int): Boolean {
        // 自己友：同一個 uid（我哋自己個 process）一定放行。
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

        // 系統 app（預載喺 /system 嘅車機組件）直接信 —— 佢哋改唔到，
        // 而且唔同 OEM ROM 會用自己嘅 platform key 簽。
        if (isSystemApp(pm, clientPackageName)) return true

        return signatureOf(pm, clientPackageName) in GOOGLE_SIGNATURES
    }

    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean = try {
        val flags = pm.getApplicationInfo(pkg, 0).flags
        (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** 攞個 package 現行簽名嘅 SHA-256（細階十六進制）。 */
    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun signatureOf(pm: PackageManager, pkg: String): String? = try {
        val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.let { info ->
                    if (info.hasMultipleSigners()) info.apkContentsSigners
                    else info.signingCertificateHistory
                }
                ?.firstOrNull()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        sig?.let {
            MessageDigest.getInstance("SHA-256")
                .digest(it.toByteArray())
                .joinToString("") { b -> "%02x".format(b) }
        }
    } catch (_: Exception) {
        null
    }
}
