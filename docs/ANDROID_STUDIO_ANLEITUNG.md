# ASDS Mobile 2.0.1 – Entwicklungs-, Build- und Signieranleitung

## 1. Projekt aus GitHub öffnen
Repository: https://github.com/fschneggenburger-collab/asds-mobile-android
Branch: `develop/android-studio-2.0.1-maintainable`.

In GitHub den Branch wählen und **Code > Download ZIP** verwenden, oder per Git:
```bash
git clone --branch develop/android-studio-2.0.1-maintainable https://github.com/fschneggenburger-collab/asds-mobile-android.git
```
In Android Studio **Open** wählen, den Ordner mit `settings.gradle.kts` öffnen
(nicht den Unterordner `app` und nicht das historische Projekt 1.2.1).

## 2. Entwicklungsumgebung
- Android Studio mit Android-Gradle-Plugin-8.7.3-Unterstützung
- **Gradle JDK: 17** unter Settings > Build, Execution, Deployment > Build Tools > Gradle.
- Android SDK Plattform 35 und Build-Tools 35.0.0.
- Internet für die einmalige Auflösung der Gradle-, Kotlin-, Room- und AndroidX-Abhängigkeiten.
- Gradle 8.9 wird über `gradlew` / `gradlew.bat` automatisch bereitgestellt.

Normaler Entwicklungsablauf: Datei unter `app/src/main/java` bearbeiten, **Sync Project
with Gradle Files**, **Run app** auf Emulator/Testgerät. Keine Python-Skripte nötig.
Das ursprüngliche Package `ch.asds.mobile` darf nicht umbenannt werden.

## 3. Testen / APK erstellen
Windows-Terminal im Projektordner:
```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```
Debug: `app/build/outputs/apk/debug/app-debug.apk`.
Release (unterschriftslos): `app/build/outputs/apk/release/app-release-unsigned.apk`.

Achtung: Debug-APK wird mit lokalem Android-Debug-Zertifikat signiert; sie ist
**nicht** als direktes Upgrade über die signierte ASDS-2.0.0-App geeignet.

## 4. Mit bestehendem ASDS-Keystore signieren
Die 2.0.0-APK verwendet ein Release-Zertifikat:
- Subject: `CN=ASDS Mobile Release, OU=ASDS Mobile, O=Aufsuchende Soziale Dienste Schneggenburger, C=CH`
- SHA-256: `B3:BC:B0:F1:7F:66:91:D8:52:56:15:B8:53:FF:68:E9:3B:27:C0:B5:E8:80:05:48:EC:F1:95:15:A3:C5:93:C2`
- Vorheriger `versionCode` 200, neuer 201.

In Android Studio:
1. **Build > Generate Signed App Bundle / APK**.
2. **APK** auswählen.
3. **Choose existing** bzw. bestehenden **Key store path** laden.
4. Vorhandenes **Key alias**, das Keystore-Passwort und das Key-Passwort eingeben.
5. Build Variant **release**, Signaturen gemäss Android Studio auswählen.
6. APK erstellen, Zertifikat vergleichen, **zuerst isoliert testen**.

Keinen neuen Keystore anlegen, solange der bestehende nutzbar ist.
Der **private Schlüssel lässt sich nicht aus der APK wiederherstellen**.
Wenn nur ein anderer Schlüssel vorhanden ist, ist ein direktes Update ohne
Deinstallation nicht möglich. Vor einem Wechsel müssen Daten exportiert/gesichert
werden; die bestehende App darf für einen Test nicht unabsichtlich deinstalliert werden.

Prüfen mit lokalem Android SDK:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs .\app-release.apk
```
Zertifikat SHA-256 muss exakt dem oben genannten Fingerabdruck entsprechen.
Private Passwörter, `*.jks`, `*.keystore`, `keystore.properties`,
`signing.properties`, `local.properties` niemals in GitHub einchecken.

## 5. Kompatibilität und Betrieb
- Gleiche Application ID `ch.asds.mobile` und grösserer versionCode.
- Android-Datenverzeichnis, WebView-Cookies, Android-Keystore und verschlüsselte
  SharedPreferences dürfen beim In-Place-Update nicht gelöscht werden.
- `OfflineRepository` (`drafts_v1`, `queue_v1`) bleibt für 2.0.0 erhalten.
- Neue R1-Daten liegen verschlüsselt in Room, per Android-Keystore AES-GCM geschützt.
  R1-Preference-Einträge aus 2.0.1 werden beim ersten Zugriff additive übernommen.
  Altes R1-Preference-Backup wird nicht gelöscht.
- Kein `fallbackToDestructiveMigration`. Für eine künftige Room-Version
  Migration testen und explizit bereitstellen, statt DB-Daten zu verwerfen.
- WorkManager erledigt vorhandene Offline-Queue und R1-Retry bei Netzverbindung.
- Native Biometrie, Kamera, Fotos, Dokumentenauswahl, Benachrichtigungen und
  bisherige Webseiten bleiben im 2.0.1-Quellstand.
- R1-HTTP-Schnittstelle: kundenspezifische Serverprüfung, Vertragsheader,
  Idempotenz, Konflikte, Sperren und Berechtigungen im Staging testen.

## 6. Für spätere Versionen
Künftig nur Kotlin, XML, Assets und Gradle regulär bearbeiten; `versionCode`
erhöhen und `versionName` anpassen. Der alte Python-Releasegenerator wird
**nicht** mehr ausgeführt. Standardbuild mittels Android Studio oder
`./gradlew`. Signieren weiterhin ausschliesslich lokal mit dem ASDS-Release-Keystore.
Kein automatischer Play-Store-Upload im CI-Prozess.
