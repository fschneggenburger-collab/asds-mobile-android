# ASDS Mobile – keep WebView / file-chooser related classes
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
