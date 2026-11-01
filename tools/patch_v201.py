#!/usr/bin/env python3
"""Deterministically apply Android 2.0.1 additions AFTER legacy 1.7/1.8 source preparation.

The historical source generator is preserved for reproducibility only.
Never run this against the installed APK or live ERP.
"""
from pathlib import Path

def replace(path, old, new):
    p=Path(path)
    s=p.read_text(encoding="utf-8")
    if new in s: return
    if s.count(old)!=1:
        raise SystemExit(f"2.0.1 patch mismatch: {path}: {old[:70]!r} ({s.count(old)} matches)")
    p.write_text(s.replace(old,new,1),encoding="utf-8")

build="app/build.gradle.kts"
replace(build,"versionCode = 90","versionCode = 201")
replace(build,'versionName = "1.8.0"','versionName = "2.0.1"')
manifest="app/src/main/AndroidManifest.xml"
replace(manifest,"    </application>",
"""        <activity
            android:name=".R1CaptureActivity"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />
    </application>""")
main="app/src/main/java/ch/asds/mobile/MainActivity.kt"
replace(main,'userAgentString = "$userAgentString ASDSMobile/1.8.0"',
    'userAgentString = "$userAgentString ASDSMobile/${BuildConfig.VERSION_NAME}"')
replace(main,'''            getString(R.string.quick_leadership)
''','''            getString(R.string.quick_leadership),
            getString(R.string.quick_r1_time)
''')
replace(main,'''                    8 -> showNativeLeadershipMenu()
''','''                    8 -> showNativeLeadershipMenu()
                    9 -> startActivity(Intent(this, R1CaptureActivity::class.java))
''')
sync="app/src/main/java/ch/asds/mobile/SyncWorker.kt"
replace(sync,'''        repository.setLastSync()
        return if (hadTemporaryFailure) Result.retry() else Result.success()
''','''        val r1Transient = R1Client.sync(applicationContext)
        repository.setLastSync()
        return if (hadTemporaryFailure || r1Transient) Result.retry() else Result.success()
''')
strings="app/src/main/res/values/strings.xml"
replace(strings,"</resources>",'''    <string name="quick_r1_time">R1: Offline-Zeit, Kilometer und Status</string>
</resources>''')
# source parity gate: do not erase any native security, upload or offline components
assert "showNativeSecurityMenu" in Path(main).read_text()
assert "showNativeLeadershipMenu" in Path(main).read_text()
assert "WebChromeClient" in Path(main).read_text()
assert "BiometricPrompt" in Path("app/src/main/java/ch/asds/mobile/SecurityController.kt").read_text()
assert "enqueueForm" in Path("app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt").read_text()
assert "R1Client.sync" in Path(sync).read_text()
print("ASDS Android 2.0.1 R1 source patch: applied, legacy features retained")
