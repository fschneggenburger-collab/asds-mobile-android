#!/usr/bin/env python3
from pathlib import Path

def require(path,*markers):
    p=Path(path); s=p.read_text(encoding="utf-8")
    missing=[m for m in markers if m not in s]
    if missing: raise SystemExit(f"Missing verified 2.0.0/R1 markers in {path}: {missing}")

require("app/build.gradle.kts","versionCode = 201",'versionName = "2.0.1"','applicationId = "ch.asds.mobile"')
require("app/src/main/assets/offline_capture.html",
    "expense_customer","expense_business","protocol_draft","receipt","trip_type","enqueueForm")
require("app/src/main/java/ch/asds/mobile/OfflineRepository.kt",
    "saveDraft","loadDraft","clearDraftPrefix","enqueue","listQueueForUi","readyQueueRaw")
require("app/src/main/java/ch/asds/mobile/MainActivity.kt",
    "Biometric","showNativeSecurityMenu","showNativeLeadershipMenu",
    "WebChromeClient","onShowFileChooser","R1CaptureActivity",
    "startAuthenticatedDownload","ASDSMobile/2.0.1")
require("app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt",
    "v2_final","offline_capture_v1","getOfflineConfig","enqueueForm")
require("app/src/main/java/ch/asds/mobile/SecurityController.kt","BiometricPrompt")
require("app/src/main/java/ch/asds/mobile/WorkerScheduler.kt","PeriodicWorkRequestBuilder")
require("app/src/main/java/ch/asds/mobile/SyncWorker.kt","R1Client.sync","deviceStatus","Result.retry")
require("app/src/main/java/ch/asds/mobile/R1Client.kt",
    "X-ASDS-Contract","X-ASDS-Client-Version","sync/batch.php","retryable")
require("app/src/main/java/ch/asds/mobile/R1Store.kt",
    "r1_queue_v1","payload_hash","base_revision","kmAmount","saveTemplate")
require("app/src/main/java/ch/asds/mobile/R1CaptureActivity.kt",
    "billable_km","km_rate","appointment_id","autosave","conflict")
require("app/src/main/AndroidManifest.xml",'R1CaptureActivity','android:exported="false"')
print("2.0.0 feature preservation + R1 static contract: PASS")
