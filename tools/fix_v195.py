#!/usr/bin/env python3
"""Apply ASDS Mobile Android 1.9.5 release-candidate hardening on top of 1.9.4."""
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

replace_once('app/build.gradle.kts', 'versionCode = 104', 'versionCode = 105', 'version code')
replace_once('app/build.gradle.kts', 'versionName = "1.9.4"', 'versionName = "1.9.5"', 'version name')
replace_once('app/src/main/java/ch/asds/mobile/MainActivity.kt', 'ASDSMobile/1.9.4', 'ASDSMobile/1.9.5', 'user agent')

# Capabilities: this is the V2 release-candidate generation.
for rel in [
    'app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt',
    'app/src/main/java/ch/asds/mobile/ServerClient.kt',
]:
    p = Path(rel)
    text = p.read_text(encoding='utf-8')
    if 'release_candidate_v2' not in text:
        marker = '.put("more_v2")'
        if marker not in text:
            raise SystemExit(f'1.9.4 capability marker not found in {rel}')
        text = text.replace(marker, marker + '.put("release_candidate_v2").put("configured_trip_labels")', 1)
    p.write_text(text, encoding='utf-8')

# Resolve trip labels from the cached server configuration first. This removes the
# obsolete native fallback "other -> Sonstiges" and keeps labels centrally configured.
p = Path('app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt')
text = p.read_text(encoding='utf-8')
pattern = re.compile(
    r'    @JavascriptInterface\n    fun labelForTripType\(code: String\): String \{.*?\n    \}\n\n    @JavascriptInterface\n    fun getDiagnostics',
    re.S,
)
replacement = r'''    @JavascriptInterface
    fun labelForTripType(code: String): String {
        val key = code.trim().lowercase()
        try {
            val config = JSONObject(repository.offlineConfig())
            val types = config.optJSONArray("trip_types")
            if (types != null) {
                for (index in 0 until types.length()) {
                    val item = types.optJSONObject(index) ?: continue
                    val itemCode = item.optString("code", item.optString("id", "")).trim().lowercase()
                    if (itemCode == key) {
                        val label = item.optString("label", "").trim()
                        if (label.isNotBlank()) return label
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to generic built-in labels when no cached config exists yet.
        }
        return when (key) {
            "asds", "asds_internal", "asds-internal" -> "ASDS intern"
            "commute_asds", "asds_commute" -> "Arbeitsweg ASDS"
            "commute_other", "other_employer", "employer_other" -> "Arbeitsweg anderer Arbeitgeber"
            "private", "privat" -> "Privat"
            "business", "business_other", "dienstlich" -> "Geschäftlich"
            "other", "sonstiges", "misc" -> "Andere Fahrt"
            else -> code.replace('_', ' ').trim().split(Regex("\\s+")).joinToString(" ") { part -> part.lowercase().replaceFirstChar { it.titlecase() } }
        }
    }

    @JavascriptInterface
    fun getDiagnostics'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit('labelForTripType function block not found')

# Include freshness of cached reference data in diagnostics for offline troubleshooting.
needle = '.put("compatibility", AppSettings.compatibilityState(activity))'
if 'offlineConfigAt' not in text.split('fun getDiagnostics',1)[1]:
    if needle not in text:
        raise SystemExit('diagnostics compatibility marker not found')
    text = text.replace(needle, '.put("offlineConfigAt", repository.offlineConfigAt())\n            ' + needle, 1)

p.write_text(text, encoding='utf-8')
print('ASDS Mobile Android 1.9.5 release-candidate hardening applied (versionCode 105)')
