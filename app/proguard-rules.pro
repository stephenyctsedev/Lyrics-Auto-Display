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
# LyricsSession 特別重要：佢唔喺 manifest 入面，只係由 Car App Library
# 經 CarAppService.onCreateSession() 建立，所以冇 manifest reference 幫 R8 keep 住。
-keep class com.stephen.autolyrics.media.NotificationMediaWatcher { *; }
-keep class com.stephen.autolyrics.car.LyricsCarAppService { *; }
-keep class com.stephen.autolyrics.car.LyricsSession { *; }
-keep class com.stephen.autolyrics.car.CarLyricsScreen { *; }
