# ASDS Mobile 2.0.0 → 2.0.1 – Test- und Freigabematrix

**Status:** Build-/statische Tests können CI-seitig automatisiert laufen.
**Noch nicht durchgeführt:** signaturidentisches Geräte-Update, Integration
am produktionsgleichen Testserver, End-to-End-ERP-Abrechnung.
Nicht auf dem produktiven ERP testen.

## A. Quellcode, Versionen, Build
- [ ] `./gradlew :app:assembleDebug` ohne Python.
- [ ] `./gradlew :app:assembleRelease` ohne Python.
- [ ] `./gradlew :app:testDebugUnitTest` (Geldberechnung).
- [ ] `./gradlew :app:assembleDebugAndroidTest` (Test-APK erzeugen).
- [ ] In Android Studio Gradle Sync mit JDK 17 / SDK 35.
- [ ] Package `ch.asds.mobile`, versionCode 201, versionName 2.0.1.
- [ ] Zertifikat der signierten Release-APK SHA-256 identisch mit 2.0.0.

## B. Offline-Datenerhalt (auf isoliertem Testgerät)
- [ ] Signierte **2.0.0** installieren und koppeln.
- [ ] Offline-Eintrag für Arbeitszeit, Spesen, Fahrt und Protokoll sowie
      Beleg erstellen, teils nur als Entwurf, teils queued.
- [ ] Biometrie aktivieren, Cookies und Session prüfen.
- [ ] Ohne Deinstallation signaturgleich auf signierte 2.0.1 aktualisieren.
- [ ] Vor Netzwerkaktivierung: alte Entwürfe, offene Queue, Dateibelege,
      Session, UI, Biometrie vorhanden.
- [ ] Native R1-Erfassung offline öffnen, Kilometer und CHF-Ansatz eintragen,
      Termin zuordnen, Vorlage sichern, App beenden und neu starten.
- [ ] R1 Room-DB enthält nur **verschlüsselte** JSON-Werte; altes
      `queue_v1` / `drafts_v1` unangetastet.
- [ ] Flugmodus aus, R1 nur einmal ins Test-ERP übernehmen, Statusanzeigen prüfen.
- [ ] Neustart, Logout, Sessionablauf, verschlüsselte Queue und Recovery testen.

## C. Synchronisation Vertrag 1.0 auf STAGING
- [ ] Capabilities/Versionierung und die tatsächlich freigeschalteten API-Pfade.
- [ ] Kunden/Termine-Delta über mehr als eine Seite, Tombstones/Berechtigungsscope.
- [ ] 200/207, Teilfehler, 401/403/426, 409 Revisionskonflikt.
- [ ] 429/5xx mit Backoff und Retry-After; fachliche Fehler ohne Auto-Replay.
- [ ] Gleicher source_key/payload_hash zweimal → ERP speichert genau einen Eintrag.
- [ ] Gleicher source_key, geänderter Hash → DUPLICATE_MISMATCH.
- [ ] Bekanntes fehlgeschlagenes Objekt → /sync/status und /sync/retry.
- [ ] Monatsabschluss, Rechnungssperre, Fremdobjektberechtigung, Freigabe.
- [ ] Kein automatisches Erzeugen abrechenbarer Zeit aus Fahr-/Anwesenheitszeit.
- [ ] Offline-Foto/Mehrfachdatei/PDF/Kamera, Download und native Navigation.

## D. Room / Sicherheit
- [ ] Emulator-Instrumentation: `R1RoomEncryptionTest`.
- [ ] App-Daten entschlüsseln ohne Android-Keystore unmöglich.
- [ ] Test der Room-Versionierung bei nächster Schemaerweiterung mit echter Migration.
- [ ] Keine privaten Schlüssel/Passwörter im Repository, keine Logs mit Payloads.
- [ ] Biometrie sperrt auch neuen R1-Erfassungsbildschirm.

## Freigabe
Erst nach A–D, Testbericht, vorhandenem Release-Keystore und ausdrücklicher
Freigabe produktionsnah installieren. CI-Build allein ist keine Produktivfreigabe.
