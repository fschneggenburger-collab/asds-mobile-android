# ASDS Mobile Android 2.0.1 – R1 Staging / Integrationsbericht

Stand: 01.11.2026. **Keine Produktivfreigabe und kein Merge.**

## Quellcodeherkunft / entscheidende Abweichung
Der Default-Branch `main` hat `versionName=1.8.0` und `versionCode=90`. Er ist **nicht** der Quellstand der gelieferten APK 2.0.0.
Der historische GitHub-Branch `agent/android-v2-release-series` enthält hingegen
`tools/fix_release_series.py`, `fix_v193.py`, `fix_v194.py`,
`fix_v195.py`, `fix_v200_final.py` und die lokale
`app/src/main/assets/offline_capture.html`.
Der GitHub-Action-Lauf #31328185816 für Android 2.0.0 Final war erfolgreich.
Auf dieser Basis wurde ausschliesslich der Entwicklungsbranch
`develop/asds-mobile-2.0.1-r1-v2` erstellt.

Das v2-Buildverfahren rekonstruiert den 1.7/1.8-Quellstand deterministisch mit
historischen Skripten und wendet dann die 1.9/2.0-Erweiterungen an. Nach
`fix_v200_final.py` ergänzt `patch_v201_v2.py` die R1-Änderungen.
Somit wird nicht versucht, die 2.0.0-APK aus dem veralteten main-Branch
durch eine abgespeckte Neuentwicklung zu ersetzen.

## APK-Funktionsvergleich
Quelle A: gelieferte signierte `ASDS_Mobile_Android_2.0.0_Final(1)(1).apk`;
SHA-256: `3eb8645033cbf7223b08b0740c77249470496fd1101a8ec5a5f2d13c001e9ef8`.
Quelle B: GitHub 2.0.0-Release-Branch und dessen Buildpipeline.

| Funktion | APK 2.0.0 | Release-Serie/2.0.1 |
|---|---|---|
| WebView-Navigation / BottomNavigation | vorhanden | erhalten |
| Fotos, Kamera, PDF, Mehrfachdatei-Upload | vorhanden | erhalten |
| Biometrische Sperre / PIN | vorhanden | erhalten, separates R1-Fenster zusätzlich geschützt |
| Verschlüsselte Entwürfe | vorhanden | altes `drafts_v1` unverändert |
| Verschlüsselte Offline-Queue / WorkManager | vorhanden | `queue_v1` und Scheduler unverändert |
| Benachrichtigungen / Geräteverwaltung | vorhanden | erhalten |
| Offline-Erfassung Zeit / Kundenspesen / Geschäftsausgaben / Fahrten / Protokoll | vorhanden (lokales HTML) | vorhandenes HTML und Legacy-Synchronisation erhalten |
| R1 abrechenbare km / Ansatz / Betrag | nicht in 2.0-HTML | zusätzliche native Zeiterfassung 2.0.1 |
| Terminverknüpfung bei Leistung | nicht in 2.0-HTML | zusätzliche native Zeiterfassung 2.0.1 |
| Zeiterfassungsvorlagen | nicht nachgewiesen | lokal verschlüsselte Vorlagen neu |
| R1 Sync source_key/payload_hash/revision | nicht in altem Pfad | separater Contract-1.0-Client |
| Konfliktstatus, Fachfehler, Retry 429/5xx | Legacy-Queue separat | R1 separate Queue mit Konfliktstatus |
| Room-Datenbank | Room-Bibliotheksmetadaten in APK | kein belastbarer Nachweis einer tatsächlich genutzten Room-Tabelle; bestehende verschlüsselte Daten nicht migriert |

## Zusätzliche R1-Komponenten
- `R1Store.kt`: verschlüsselte separate R1-Queue, stabile UUID je Buchung, Payload-Hash, Entwürfe und Vorlagen, Dezimal-Betragsberechnung (kein JSON-Float).
- `R1Client.kt`: Contract-1.0-Header, Session-Cookie des vorhandenen gekoppelten Geräts, GET-Deltas mit Cursor, idempotente Einzel-Batches, spezifische Statuscodes. Maximal 7 Versuche mit begrenztem exponentiellen Backoff und `Retry-After`.
- `R1CaptureActivity.kt`: Offline-Leistung, Kunde, Terminreferenz, Stunden, abrechenbare Kilometer, Ansatz, Betrag, Vorlagen, Auto-Entwurf und Statusansicht. Fachliche Konflikte erfordern manuelle Prüfung.
- `patch_v201_v2.py`: versionCode 201, versionName 2.0.1, additive Activity und vorhandenen Worker einbinden.
- `build-2.0.1-r1.yml`: GitHub Actions staging-only, Debug und **unsigned** Release, keine Veröffentlichung.

## Wichtige Integrations-/Freigabebedingungen
1. PHP-Mobile-Server 2.0.1 muss separat auf einer Testinstanz mit Vertrag 1.0 bereitgestellt sein; Android führt keine ERP-Migration durch.
2. E2E-Tests für `GET /capabilities`, Kunden/Termine-Paginierung, `POST /sync/batch`, `GET /sync/status`, 207, 409, 426, 429, Offline-Replay, Monatsabschluss, bereits abgerechnete Leistung.
3. Prüfen, ob die vorhandene WebView-Session an `api/v1` (selbe Domain) für die R1-Geräteauthentifizierung reicht. Ein 401/403 darf keine Daten löschen.
4. Echte Integration ERP 10.38 + Mobility 1.1.5 sowie Dolibarr 23.0.3 und 24.0.2 fehlt noch. Nicht gegen Produktivdaten testen.
5. Kein privater Release-Keystore im Repository. **Debug-APK ist nur für separate Neuinstallation und kann die bereits release-signierte 2.0.0 nicht aktualisieren.** Die Release-APK ist zunächst unsigniert. Update ohne Datenverlust erfordert denselben Signing-Key wie APK 2.0.0, Signierzertifikat SHA-256 `B3:BC:B0:F1:7F:66:91:D8:52:56:15:B8:53:FF:68:E9:3B:27:C0:B5:E8:80:05:48:EC:F1:95:15:A3:C5:93:C2`.
6. Mobile-Dateien `drafts_v1` und `queue_v1` werden nicht gelöscht oder in R1 migriert; alte Offline-Einträge bleiben über den Legacy-Syncweg kompatibel. Ein manueller Regressionstest beim Signatur-identischen Update ist Pflicht.
7. Die Vertrag-1.0-Deltas begrenzen eine Antwort auf 200 Datensätze, der Client folgt bis zu 25 Cursorn (max. 5000), ohne unvollständige Listen als vollständig auszugeben.
8. Laufzeit-, Geräte-, DB- und Regressionstests einschliesslich Rechnungs- und Freigabesperren sind **nicht** durch die reine Source-/CI-Prüfung ersetzt.

## Nicht behauptet
Keine Signatur mit dem privaten bestehenden 2.0.0-Key, kein Play-Store-Release, keine Änderung des ERP, keine bestätigte Datenmigration, keine Freigabe für Dolibarr 24.0.2.

## Testmatrix vor Freigabe
- Build: Android CI debug + release unsigned, Artefakt SHA256.
- Migration: 2.0.0 mit vorhandenen Offline-Entwürfen, queued uploads und Biometrie signaturgleich auf 2.0.1 aktualisieren.
- Offline: Flugmodus → 2 Erfassungen und Vorlage → Gerät neustarten → Entwürfe/Status erhalten → WLAN → einmalige ERP-Anlage.
- 207: gemischter Batch mit Erfolg und Fachfehler.
- Idempotenz: gleiche UUID/Hash wiederholen → ein ERP-Eintrag; andere Nutzdaten/gleiche UUID → DUPLICATE_MISMATCH.
- Revisionskonflikt: ERP ändert Leistung während Mobilgerät offline → CONFLICT_REVISION, keine Überschreibung.
- Rechte/Entity/Monatsabschluss/Invoice: Fehler erscheinen als final und werden nicht automatisch neu gespielt.
- 429/5xx: Backoff, Retry-After, Abbruch nach Grenzwert; 401/403/426 nicht wiederholen.
- Belege: Bild, Kamera, PDF und Mehrfachauswahl in alter und neuer App vergleichen.
- Sicherheit: App sperren, Hintergrund/Wiederaufnahme, Verweigerung biometrischer Authentifizierung, R1-Fenster nicht ohne Auth lesbar.
