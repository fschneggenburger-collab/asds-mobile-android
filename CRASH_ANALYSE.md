# Crash-Analyse APK 1.1.0 (handgebauter DEX)

## Befund

| Metrik | Wert |
|--------|------|
| `classes.dex` Größe | 3788 Bytes |
| DEX Magic / Checksums | gültig (`dex\n035`, Adler32 + SHA-1 korrekt) |
| Exception-Handler (`tries_size`) | **0 in allen Methoden** |
| Relevante Methoden | `onShowFileChooser`, `startCameraCapture`, `startSystemPhotoPicker`, `onActivityResult`, `cancelFileChooser` |

## Konkrete Crash-Ursache

Beim Antippen eines `<input type="file">` ruft die WebView `WebChromeClient.onShowFileChooser` auf.

Der handgebaute Code (build.py → DEX):

1. Speichert den `ValueCallback`.
2. Prüft `FileChooserParams.isCaptureEnabled()`.
3. Startet entweder `ACTION_IMAGE_CAPTURE` (MediaStore-Insert + Intent) oder `android.provider.action.PICK_IMAGES`.

**Keine einzige dieser Methoden besitzt eine Exception-Table** (`tries_size = 0`).  
Jede der folgenden Situationen endet daher in einem unbehandelten Runtime-Abort:

- `ActivityNotFoundException` – Photo Picker / Kamera-App nicht auflösbar
- `SecurityException` – MediaStore-Insert oder URI-Grant
- `IllegalArgumentException` / `NullPointerException` – fehlgeschlagener ContentResolver.insert → null-URI
- Verifier-/Register-Probleme in Randpfaden (historisch dokumentiert)

Der erste allgemeine Ansatz öffnete zudem Astro/Dateimanager, weil `FileChooserParams.createIntent()` bzw. `ACTION_GET_CONTENT` genutzt wurde – in 1.1.0 zwar auf `PICK_IMAGES` umgestellt, aber ohne try/catch und ohne Fallback.

## Warum der DEX-Generator nicht repariert wurde

Laut Auftrag: *„Bitte diesen DEX-Generator NICHT weiter patchen.“*  
Handgebaute DEX-Generatoren sind fehleranfällig (Register-Allokation, outs_size, try-ranges, Alignment). Ein reguläres Android-Gradle-Projekt eliminiert diese Klasse von Fehlern vollständig.

## Lösung

Vollständiges Kotlin-Projekt mit:

- AndroidX `ActivityResultContracts` (PickVisualMedia, TakePicture, OpenDocument)
- FileProvider statt roher MediaStore-URI
- try/catch + Toast bei jedem Launch-Pfad
- sauberes einmaliges Beantworten des `ValueCallback`
- `configChanges` + WebView state save/restore für Rotation
