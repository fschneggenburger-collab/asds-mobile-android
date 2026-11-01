#!/usr/bin/env python3
"""Deterministic source contract and regression guard for CI (no external libs)."""
from pathlib import Path

def has(path,*needles):
    text=Path(path).read_text(encoding="utf-8")
    missing=[n for n in needles if n not in text]
    if missing: raise SystemExit(f"{path} missing: {missing}")

has("app/build.gradle.kts",'versionCode = 201','versionName = "2.0.1"',
    'applicationId = "ch.asds.mobile"')
has("app/src/main/java/ch/asds/mobile/MainActivity.kt",
    "showNativeSecurityMenu","showNativeLeadershipMenu",
    "R1CaptureActivity","WebChromeClient","onShowFileChooser","startAuthenticatedDownload",
    "AppCompatDelegate", "ensureV171SecurityDefaults","Biometric")
has("app/src/main/java/ch/asds/mobile/SecurityController.kt","BiometricPrompt")
has("app/src/main/java/ch/asds/mobile/OfflineRepository.kt",
    "saveDraft", "loadDraft", "clearDraftPrefix","enqueue","listQueueRaw", "markQueueFailure")
has("app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt",
    "enqueueForm","retrySync","getOfflineConfig","saveDraft")
has("app/src/main/java/ch/asds/mobile/SyncWorker.kt",
    "deviceStatus","R1Client.sync","Result.retry")
has("app/src/main/java/ch/asds/mobile/WorkerScheduler.kt","PeriodicWorkRequestBuilder")
has("app/src/main/java/ch/asds/mobile/R1Client.kt",
    "X-ASDS-Contract","X-ASDS-Client-Version","sync/batch.php","source_key")
has("app/src/main/java/ch/asds/mobile/R1Store.kt",
    "r1_queue_v1","pending","retryable","payload_hash","base_revision","kmAmount")
has("app/src/main/java/ch/asds/mobile/R1CaptureActivity.kt",
    "billable_km","km_rate","appointment_id","saveTemplate","autosave","conflict")
has("app/src/main/AndroidManifest.xml", 'R1CaptureActivity', 'android:exported="false"')
assert 'clearAll()' not in Path("app/src/main/java/ch/asds/mobile/R1Client.kt").read_text()
print("ASDS Mobile 2.0.1 R1 structural parity gates: PASS")
