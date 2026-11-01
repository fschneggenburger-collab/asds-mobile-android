#!/usr/bin/env python3
"""ASDS 2.0.1 R1 additive patch on verified 2.0.0 final source generation."""
from pathlib import Path

def change(file, old, new):
    p=Path(file); s=p.read_text(encoding="utf-8")
    if new in s: return
    count=s.count(old)
    if count != 1: raise SystemExit(f"2.0.1 source parity mismatch {file}: {old[:60]!r} count={count}")
    p.write_text(s.replace(old,new,1),encoding="utf-8")

build="app/build.gradle.kts"
change(build,"versionCode = 200","versionCode = 201")
change(build,'versionName = "2.0.0"','versionName = "2.0.1"')
main="app/src/main/java/ch/asds/mobile/MainActivity.kt"
change(main,"ASDSMobile/2.0.0", "ASDSMobile/2.0.1")
# Preserve ALL existing quick actions (security, leadership, announcements, feedback).
change(main, 'headerMore.setOnClickListener { showQuickActions() }',
'''headerMore.setOnClickListener { showQuickActions() }
        headerMore.setOnLongClickListener {
            startActivity(Intent(this, R1CaptureActivity::class.java))
            true
        }''')
# Also add an explicit menu action after the verified 2.0.0 leadership entry.
change(main,'''            getString(R.string.quick_leadership)
''','''            getString(R.string.quick_leadership),
            getString(R.string.quick_r1_time)
''')
change(main,'''                    8 -> showNativeLeadershipMenu()
''','''                    8 -> showNativeLeadershipMenu()
                    9 -> startActivity(Intent(this, R1CaptureActivity::class.java))
''')
sync="app/src/main/java/ch/asds/mobile/SyncWorker.kt"
change(sync,'''        return if (hadTemporaryFailure) Result.retry() else Result.success()
''','''        val r1Transient = R1Client.sync(applicationContext)
        return if (hadTemporaryFailure || r1Transient) Result.retry() else Result.success()
''')
manifest="app/src/main/AndroidManifest.xml"
change(manifest,"    </application>",
'''        <activity android:name=".R1CaptureActivity"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />
    </application>''')
strings="app/src/main/res/values/strings.xml"
change(strings,"</resources>",
'''    <string name="quick_r1_time">R1: Zeiterfassung, Kilometer und Sync-Status</string>
</resources>''')
# Fail closed if any historical 2.0.0 feature disappeared during source generation.
for p, needles in {
"app/src/main/assets/offline_capture.html":["expense_business","protocol_draft","receipt","enqueueForm"],
"app/src/main/java/ch/asds/mobile/MainActivity.kt":["showNativeSecurityMenu","showNativeLeadershipMenu","onShowFileChooser","WebChromeClient"],
"app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt":["offline_capture_v1","v2_final","getOfflineConfig"],
"app/src/main/java/ch/asds/mobile/SecurityController.kt":["BiometricPrompt"],
"app/src/main/java/ch/asds/mobile/SyncWorker.kt":["R1Client.sync","deviceStatus"],
}.items():
    text=Path(p).read_text(encoding="utf-8")
    for needle in needles:
        if needle not in text: raise SystemExit(f"2.0.0 regression in {p}: {needle}")
print("ASDS Android 2.0.1 source generated from 2.0.0 final, R1 additions applied")
