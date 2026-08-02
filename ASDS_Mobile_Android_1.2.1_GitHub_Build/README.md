# ASDS Mobile Android 1.2.1

Reguläres Android-Studio-/Gradle-Projekt (Kotlin) als Ersatz für den handgebauten DEX-Generator.

## Anforderungen erfüllt

| # | Anforderung | Umsetzung |
|---|-------------|-----------|
| 1 | package `ch.asds.mobile` | `applicationId` + namespace |
| 2 | minSdk 31, targetSdk 35 | `app/build.gradle.kts` |
| 3 | WebView + JS + DOM Storage + Cookies | `MainActivity.configureWebView()` |
| 4 | Session bleibt bei Update erhalten | Cookies + DOM Storage; `singleTask` + `configChanges` |
| 5 | `onShowFileChooser` | korrekt implementiert, gibt `true` zurück |
| 6 | image/* ohne capture → Photo Picker | `ActivityResultContracts.PickVisualMedia` |
| 7 | image/* + capture → native Kamera | `TakePicture` + FileProvider-URI |
| 8 | image/* + pdf → nativer Dialog | AlertDialog: Fotos / Kamera / PDF |
| 9 | PDF → OpenDocument | `ActivityResultContracts.OpenDocument` |
| 10 | Kein READ_MEDIA_IMAGES | nicht deklariert |
| 11 | Keine CAMERA-Permission | nicht deklariert (System-Kamera-App) |
| 12 | ValueCallback genau einmal | `deliverCallback` / `cancelPendingCallback` |
| 13 | Alten Callback vor neuer Auswahl nullen | `cancelPendingCallback()` am Anfang von `onShowFileChooser` |
| 14 | Exceptions abfangen | try/catch + Toast |
| 15 | Rotation | `configChanges` + `saveState`/`restoreState` |
| 16 | FileProvider | Manifest + `res/xml/file_paths.xml` |
| 17 | URI-Flags | FileProvider + TakePicture-Contract |
| 18 | onShowFileChooser → true | ja |
| 19 | Vollständiger Source | dieses Projekt |
| 20 | Kein privater Schlüssel | nur Source; Signatur separat |

## Server-Inputs (expenses.php)

- `receipt_file` → `accept="image/*,application/pdf"` → Dialog mit drei Optionen
- `receipt_camera` → `accept="image/*" capture="environment"` → direkt Kamera

## Build-Anleitung

### Voraussetzungen

- Android Studio Ladybug (2024.2+) oder neuer, bzw. JDK 17 + Android SDK 35
- Kein privater Signierschlüssel im Repo – Release-Signing erfolgt separat mit dem vorhandenen Zertifikat

### Debug-APK bauen

```bash
cd ASDS_Mobile_Android
./gradlew :app:assembleDebug
# Ausgabe: app/build/outputs/apk/debug/app-debug.apk
```

### Release-APK (ohne Signing-Config → unsigned)

```bash
./gradlew :app:assembleRelease
```

Anschliessend mit dem bestehenden ASDS-Zertifikat signieren (jarsigner / apksigner), analog zum bisherigen Prozess.

### Installation über bestehende App

`applicationId` bleibt `ch.asds.mobile`. VersionCode 22 > 21 (1.2.0).  
**Nicht deinstallieren** – Cookies/DOM-Storage der WebView-Sitzung bleiben erhalten, sofern die App aktualisiert (nicht gelöscht) wird.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Analyse der Crash-Ursache (APK 1.1.0)

Die handgebaute APK 1.1.0 (`classes.dex` nur 3788 Bytes) enthält:

- **0 try/catch-Handler** (`tries_size = 0` für alle Methoden inkl. `onShowFileChooser`, `startCameraCapture`, `startSystemPhotoPicker`, `onActivityResult`).
- Jede `ActivityNotFoundException`, `SecurityException` oder `IllegalArgumentException` (z. B. Photo-Picker auf manchen Geräten, fehlgeschlagener MediaStore-Insert, Intent-Auflösung) führt zum sofortigen Absturz.
- Kein `onRequestPermissionsResult` und keine robuste Intent-Absicherung.
- Register-/outs-Probleme und fehlende Exception-Tables waren bereits in früheren Versionen bekannt; der DEX-Generator wurde laut Auftrag **nicht** weiter gepatcht.

Dieses Projekt ersetzt den Generator vollständig durch standardkonformes Kotlin + AndroidX Activity Result APIs.

## Projektstruktur

```
ASDS_Mobile_Android/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/ch/asds/mobile/MainActivity.kt
│       └── res/
│           ├── layout/activity_main.xml
│           ├── xml/file_paths.xml
│           ├── values/{strings,themes,colors}.xml
│           └── drawable/…
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

## Hinweise

- Photo Picker (`PickVisualMedia`) ist ab API 33 nativ; AndroidX liefert Fallback für API 31–32.
- Kamera schreibt in `cacheDir/camera/` über FileProvider – keine Speichermedien-Berechtigung nötig.
- Abbruch (Back / Dialog-Cancel) liefert `null` an den WebView-Callback ohne Absturz.
- Mehrfaches Öffnen nacheinander: alter Callback wird vor dem neuen immer mit `null` geschlossen.


## Korrekturen in 1.2.1

- Gemischte WebView-Accept-Typen werden auch dann korrekt erkannt, wenn Android/WebView sie als einen String wie `image/*,application/pdf` liefert.
- Verwaiste Kamera-Cachedateien älter als 24 Stunden werden beim Start bereinigt.
- VersionCode auf 22 erhöht.
