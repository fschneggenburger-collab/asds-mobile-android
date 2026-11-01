# ASDS Mobile Android 2.0.1 – Android Studio

**Entwicklungsbranch:** `develop/android-studio-2.0.1-maintainable`  
**Status:** Staging, kein Play-Store-Release, keine produktive ERP-Änderung.

Dies ist das **direkt baubare Android-Studio-Projekt**. Im Gegensatz zum historischen
Android-1.7–2.0.0-Releaseprozess ist die komplette Kotlin-/XML-/Asset-Implementierung
unter `app/src/main` versioniert. Der Build erzeugt sie **nicht** aus Python-Skripten.

- `applicationId`: `ch.asds.mobile` (unverändert)
- `versionName`: `2.0.1`
- `versionCode`: `201` (Android 2.0.0: 200)
- JDK 17, Gradle Wrapper 8.9, Android Gradle Plugin 8.7.3
- Kotlin 2.0.21, Room 2.6.1 / KSP, WorkManager
- Android minSdk 31 / targetSdk 35

## Android Studio
1. Den Branch klonen oder das Android-Studio-Projekt-ZIP entpacken.
2. Android Studio öffnen, **Open** wählen und den Ordner mit `settings.gradle.kts` öffnen.
3. Gradle JDK **17** wählen, Android SDK Platform **35** installieren und Gradle synchronisieren.
4. **Build > Make Project** oder **Run** für den Debug-Build.
5. Optional `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`.
6. Für den signierten Release-Build in Android Studio **Build > Generate Signed App Bundle / APK**
   wählen und **den bisherigen ASDS-Release-Keystore** laden. Niemals das Debug-Zertifikat
   für ein Update der bestehenden 2.0.0 verwenden.

**Private Schlüssel und Passwörter gehören ausschliesslich auf den lokalen PC.**
Das Repository enthält absichtlich weder einen Keystore noch Zugangsdaten oder
eine automatische Release-Signierkonfiguration.

Die konkrete Anleitung und Prüfliste stehen unter
[`docs/ANDROID_STUDIO_ANLEITUNG.md`](docs/ANDROID_STUDIO_ANLEITUNG.md) und
[`docs/TESTPLAN_UPDATE_2_0_0_ZU_2_0_1.md`](docs/TESTPLAN_UPDATE_2_0_0_ZU_2_0_1.md).

## Offline-Aufbau / Bestandssicherung
- **2.0.0-Bestand**: `OfflineRepository` und `SecureStore` (`drafts_v1`, `queue_v1`)
  sowie die Offline-Seite `app/src/main/assets/offline_capture.html` bleiben erhalten.
- **2.0.1-R1**: `R1RoomDatabase`, `R1Store`, `R1Client`, `R1CaptureActivity`.
  R1-Entwürfe, Vorlagen, Referenzlisten und Queue werden als mit Android-Keystore
  AES-GCM verschlüsselte JSON-Dokumente in Room gespeichert; ältere R1-Dokumente
  werden beim ersten Lesen aus den verschlüsselten 2.0.1-Preferences übernommen.
  Die bisherigen R1-Preferences bleiben für Rollback als verschlüsselte Kopie bestehen.
- Room erstellt **keine destruktive Migration** und verändert keine 2.0.0-Offline-Daten.
- WorkManager und SyncWorker bleiben aktiviert. R1-Synchronisation benötigt
  eine vom Server freigegebene Testumgebung und darf nicht ungeprüft mit
  produktiven ERP-Daten getestet werden.

**Wichtig:** Ein erfolgreicher Android-Build ist kein Beweis für das vollständige
End-to-End-Verhalten von Offline-Synchronisation, Server-Konflikten oder
Upgrade-Installation. Dafür sind die dokumentierten Gerätetests vorgeschrieben.

## Projektstruktur
```
app/
  build.gradle.kts
  src/main/java/ch/asds/mobile/  # Vollständiger Kotlin-Quellcode
  src/main/assets/                # Offline-Erfassungsseite
  src/main/res/                   # Android-Ressourcen
  src/test/                       # JVM-Tests
  src/androidTest/                # Room-Gerätetests (Emulator)
  schemas/                        # Exportierte Room-Schemas, falls nach CI generiert
gradle/wrapper/                    # Standard-Gradle-Wrapper
gradlew / gradlew.bat             # Normaler Build ohne Python
docs/                             # Bedienung, Signierung, Updateprüfung
.github/workflows/studio-ci.yml   # Kompilieren und Android-Studio-ZIP bereitstellen
```

Die Dateien `tools/fix_*.py` sind nur historisches Archiv im Repository und
werden weder von Android Studio noch vom neuen CI-Workflow ausgeführt.
