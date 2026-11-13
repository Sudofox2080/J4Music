# J4Music 混淆规则
-keep class com.j4.music.** { *; }
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
