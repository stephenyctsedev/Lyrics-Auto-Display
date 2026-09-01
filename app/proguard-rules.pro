# R8 只做 shrink / minify —— 唔做 string encryption 嗰類令人懷疑嘅嘢。
#
# 註：實測過冇呢個檔 release build 一樣行得通（AGP 當佢係空檔，唔會 fail），
# 而且 R8 亦冇切走任何 load-bearing class。呢度嘅規則係 defence-in-depth：
# 下面幾個 class 全部係由系統／framework 用名反射建立，唔係由我哋 code 直接 new 出嚟，
# 所以 R8 嘅 reachability 分析睇唔到佢哋真正嘅入口。將來升級 dependency 或者
# AGP 之後分析結果可能會變，明文 keep 住就唔使靠彩數。

# Car App Library 靠 reflection 攞 template / session，要保住
-keep class androidx.car.app.** { *; }

# Room 生成嘅 database implementation
-keep class * extends androidx.room.RoomDatabase { *; }

# 由系統綁定／反射建立嘅 component。
-keep class com.stephen.autolyrics.media.NotificationMediaWatcher { *; }

# Android Auto 入口。系統經 manifest 用名實例化，R8 嘅 reachability 分析
# 睇唔到有人 call 佢 —— 冇呢條規則，release build 個 service 可能被 rename
# 或者剝走，車機就 bind 唔到（debug build 唔 minify 所以睇唔出）。
-keep class com.stephen.autolyrics.auto.LyricsBrowserService { *; }

# 舊 template 路線嗰幾個 class 已經喺 manifest disable 咗，唔再需要 keep。
# 想行返嗰條路嘅話記得連呢度一齊改返。
