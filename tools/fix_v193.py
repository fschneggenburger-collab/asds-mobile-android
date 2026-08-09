#!/usr/bin/env python3
"""Apply ASDS Mobile Android 1.9.3 on top of the generated 1.9.2 source."""
from pathlib import Path
import re


def replace_once(path, old, new, label):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if new in text:
        return
    if text.count(old) != 1:
        raise SystemExit(f'{label}: expected one match, found {text.count(old)}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

replace_once('app/build.gradle.kts', 'versionCode = 102', 'versionCode = 103', 'version code')
replace_once('app/build.gradle.kts', 'versionName = "1.9.2"', 'versionName = "1.9.3"', 'version name')

# Offline reference data (including customer names) is stored through SecureStore,
# not in plain SharedPreferences. The timestamp itself stays in the local state prefs.
p = Path('app/src/main/java/ch/asds/mobile/OfflineRepository.kt')
text = p.read_text(encoding='utf-8')
if 'OFFLINE_CONFIG_KEY' not in text:
    old = '''        private const val DRAFTS_KEY = "drafts_v1"\n        private const val QUEUE_KEY = "queue_v1"'''
    new = '''        private const val DRAFTS_KEY = "drafts_v1"\n        private const val QUEUE_KEY = "queue_v1"\n        private const val OFFLINE_CONFIG_KEY = "offline_config_v1"'''
    if old not in text:
        raise SystemExit('OfflineRepository key insertion point not found')
    text = text.replace(old, new, 1)
if 'fun setOfflineConfig(value: String)' not in text:
    marker = '    @Synchronized fun clearQueue() = secureStore.put(QUEUE_KEY, JSONArray().toString())\n'
    extra = '''    @Synchronized fun setOfflineConfig(value: String) {\n        secureStore.put(OFFLINE_CONFIG_KEY, value.take(300000))\n        settings.edit().putLong("offline_config_at", System.currentTimeMillis()).apply()\n    }\n    @Synchronized fun offlineConfig(): String = secureStore.get(OFFLINE_CONFIG_KEY, "{}")\n    fun offlineConfigAt(): Long = settings.getLong("offline_config_at", 0L)\n'''
    if marker not in text:
        raise SystemExit('OfflineRepository method insertion point not found')
    text = text.replace(marker, marker + extra, 1)
p.write_text(text, encoding='utf-8')

p = Path('app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt')
text = p.read_text(encoding='utf-8')
marker = '    @JavascriptInterface\n    fun labelForTripType(code: String): String {'
extra = '''    @JavascriptInterface fun setOfflineConfig(value: String) = repository.setOfflineConfig(value)\n    @JavascriptInterface fun getOfflineConfig(): String = repository.offlineConfig()\n    @JavascriptInterface fun getOfflineConfigAt(): Long = repository.offlineConfigAt()\n    @JavascriptInterface fun openOfflineCapture() = activity.runOnUiThread { activity.openOfflineCapture() }\n\n'''
if 'fun openOfflineCapture()' not in text:
    if marker not in text:
        raise SystemExit('Bridge insertion point not found')
    text = text.replace(marker, extra + marker, 1)
old = '''        if (BuildConfig.VERSION_CODE >= 102) result.put("device_instance").put("notification_deeplink")\n        if (BuildConfig.VERSION_CODE >= 200) result.put("tripbook_v2").put("tripbook_analysis").put("tripbook_export")'''
new = '''        if (BuildConfig.VERSION_CODE >= 102) result.put("device_instance").put("notification_deeplink")\n        if (BuildConfig.VERSION_CODE >= 103) result.put("feedback").put("announcements").put("offline_capture_v1")\n        if (BuildConfig.VERSION_CODE >= 200) result.put("tripbook_v2").put("tripbook_analysis").put("tripbook_export")'''
if 'offline_capture_v1' not in text:
    if old not in text:
        raise SystemExit('Bridge capability insertion point not found')
    text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')

p = Path('app/src/main/java/ch/asds/mobile/ServerClient.kt')
text = p.read_text(encoding='utf-8')
old = '''        if (BuildConfig.VERSION_CODE >= 102) capabilities.put("device_instance").put("notification_deeplink")\n        if (BuildConfig.VERSION_CODE >= 200) capabilities.put("tripbook_v2").put("tripbook_analysis").put("tripbook_export")'''
new = '''        if (BuildConfig.VERSION_CODE >= 102) capabilities.put("device_instance").put("notification_deeplink")\n        if (BuildConfig.VERSION_CODE >= 103) capabilities.put("feedback").put("announcements").put("offline_capture_v1")\n        if (BuildConfig.VERSION_CODE >= 200) capabilities.put("tripbook_v2").put("tripbook_analysis").put("tripbook_export")'''
if 'offline_capture_v1' not in text:
    if old not in text:
        raise SystemExit('Server capability insertion point not found')
    text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')

p = Path('app/src/main/java/ch/asds/mobile/MainActivity.kt')
text = p.read_text(encoding='utf-8')
if 'ASDSMobile/1.9.3' not in text:
    if 'ASDSMobile/1.9.2' not in text:
        raise SystemExit('1.9.2 user agent not found')
    text = text.replace('ASDSMobile/1.9.2', 'ASDSMobile/1.9.3', 1)

cache_call = 'if (BuildConfig.VERSION_CODE >= 103 && url.orEmpty().startsWith(BASE_URL)) cacheOfflineConfig()'
if cache_call not in text:
    page_finished = re.search(r'override fun onPageFinished\(view: WebView\?, url: String\?\) \{.*?\n\s*\}', text, re.S)
    if not page_finished:
        raise SystemExit('onPageFinished block not found')
    block = page_finished.group(0)
    anchor = 'updateNativeShell(url.orEmpty())'
    if anchor not in block:
        raise SystemExit('onPageFinished updateNativeShell anchor not found')
    block2 = block.replace(anchor, anchor + '\n                ' + cache_call, 1)
    text = text[:page_finished.start()] + block2 + text[page_finished.end():]

error_call = 'if (BuildConfig.VERSION_CODE >= 103) openOfflineCapture() else showLoadError()'
if error_call not in text:
    simple = 'if (request?.isForMainFrame == true) showLoadError()'
    if simple in text:
        text = text.replace(simple, 'if (request?.isForMainFrame == true) {\n                    ' + error_call + '\n                }', 1)
    else:
        pattern = re.compile(r'if\s*\(request\?\.isForMainFrame\s*==\s*true\)\s*\{\s*showLoadError\(\)\s*\}', re.S)
        text, count = pattern.subn('if (request?.isForMainFrame == true) {\n                    ' + error_call + '\n                }', text, count=1)
        if count != 1:
            raise SystemExit('onReceivedError main-frame handler not found')

marker = '    override fun onSaveInstanceState(outState: Bundle) {'
methods = r'''    fun openOfflineCapture() {
        try {
            val html = assets.open("offline_capture.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            currentPageLabel = "Offline-Erfassung"
            updateSubtitle()
            errorPanel.visibility = View.GONE
            webView.visibility = View.VISIBLE
            pageProgress.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
        } catch (error: Exception) {
            Log.e(TAG, "Offline capture could not be opened", error)
            showLoadError()
            Toast.makeText(this, "Offline-Erfassung konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
        }
    }

    private fun cacheOfflineConfig() {
        val script = """
            (function(){try{fetch('https://portal.ihre-wegbegleiterin.ch/custom/asds_mobile/mobile/offline_config.php?stage=10',{credentials:'include',cache:'no-store'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()}).then(function(j){if(window.ASDSNative&&window.ASDSNative.setOfflineConfig)window.ASDSNative.setOfflineConfig(JSON.stringify(j))}).catch(function(){})}catch(e){}})();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

'''
if 'fun openOfflineCapture() {' not in text:
    if marker not in text:
        raise SystemExit('MainActivity method insertion point not found')
    text = text.replace(marker, methods + marker, 1)

p.write_text(text, encoding='utf-8')
print('ASDS Mobile Android 1.9.3 enhancements applied (versionCode 103)')
