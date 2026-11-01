# ASDS Mobile 2.0.2 – Kilometer in der Offline-Zeiterfassung

## Grundlage und tatsächlicher Fehler
Die signierte Android-App 2.0.1 enthielt im lokal verwendeten HTML-Formular
`app/src/main/assets/offline_capture.html` noch die Zeitdefinition aus
Version 2.0.0. Das separate native R1-Formular war bereits km-fähig, aber
dieses war nicht derselbe Erfassungspfad.

Die Analyse basiert auf der bereitgestellten unveränderten Serverquelle
`ASDS_2_Mobile_R1_Integration_2.0.1_2026-11-01.zip`, nicht auf einem
bestätigten Produktivserver-Deployment.

## Datenfluss 2.0.2
1. Beim Öffnen wird die HTML-Seite aus dem lokalen App-Asset geladen.
   `MainActivity.openOfflineCapture()` liest das reine Modul
   `offline_time_202.js` ebenfalls aus den **App-Assets** und bettet es
   lokal in die Seite ein – wichtig, da die WebView als Basis eine
   Server-URL verwendet und das Gerät offline sein kann.
2. `defs.time` enthält `billable_km`, `km_rate`, `appointment_id`.
   Standardansatz aus `offline_config.default_km_rate`, Terminliste
   aus `offline_config.appointments`, gefiltert nach `customer_id`.
   Fehlt ein Ansatz, wird CHF 0.70/km als ausdrücklich provisorischer
   Wert mit Hinweis angeboten; vor Übertragung ist die Änderung prüfbar.
3. `offline_time_202.js` validiert die Kilometer und den Ansatz als
   Dezimalzahlen (maximal 2 Nachkommastellen), zeigt
   `km x km_rate` in CHF auf 0.01 gerundet an. Die Vorschau ist
   informativ und **wird nicht als eigener Umsatzposten übermittelt**.
4. `buildTimePayload()` setzt `km` und `billable_km` auf den identischen
   Wert; dies sind **Aliase desselben Kilometersatzes**, nicht zwei
   Beträge. `km_rate` ist eine CHF/km-Zeichenfolge, `appointment_id`
   ist optional. Vorhandene Arbeitszeit-/Kundenfelder bleiben erhalten.
5. Das bestehende `enqueueForm` legt **einen** verschlüsselten
   `queue_v1`-Eintrag (POST, unveränderte `client_id`, `kind=time`,
   JSON-`payload`) an; der SyncWorker verwendet dieselbe Queue wie 2.0.0.
6. Der Server 2.0.1 berechnet Minuten, validiert Kunde/Termin und
   schreibt `km` und `km_rate` in die eine
   `llx_asds_time`-Leistung. Das ERP rechnet die Kilometerposition
   bei der Rechnungsstellung aus diesen beiden Feldern.
   Der Server führt Legacy-Idempotenz über `client_id` und
   `payload_hash`; R1-Sync verwendet separat
   `source_key`/`payload_hash`.
7. Erst nach einer klaren Serverbestätigung
   (HTTP 2xx plus `ok=true` und `status=success` oder
   bestätigte `target_id`) wird der Queue-Eintrag entfernt.
   Andere Antworten bleiben lokal; 429/5xx haben Backoff,
   4xx-/Konflikte werden zur manuellen Prüfung gesperrt.
   **Keine** Weiterleitung oder blosses HTTP 200 löscht lokale Daten.

## Serverkompatibilität / Update-Schranke
Die ältere produktive Installation ist noch nicht aktualisiert.
Die App darf neue Kilometer-/Termin-Daten deshalb nicht an eine
Installation senden, die diese Felder möglicherweise ignoriert.

`LegacyOfflineTimeSync.requiresV201Server()` erkennt Zeiteinträge
mit km > 0 oder Terminzuordnung.
`supportsV201Server()` verlangt aus der **lokal gespeicherten**
Offline-Serverkonfiguration:
- `ok=true`
- `contract_version="1.0"`
- `capabilities.features.billable_km_on_time=true`

Fehlt diese Bestätigung, bleibt die gesamte Zeitleistung mit unverändertem
Payload in der Queue (`waiting_server`, 5-Minuten-Wiederholungsfenster).
Nach späterer Serveraktualisierung zuerst die **Online-Seite** aufrufen,
damit die Offline-Konfiguration aktualisiert wird; dann Synchronisation
erneut anstossen. Falls eine zuvor gültige Konfiguration nachträglich
veraltet ist, kann nur ein echter Servertest / Laufzeitprüfung garantieren,
dass der aktuell laufende Server die Felder auch verarbeitet.

## Online-Zeiterfassung
Im **bereitgestellten** Mobile-Server-2.0.1-Paket enthält
`mobile/time.php` für `manual_save` und `stop_timer` bereits
`km` und `km_rate`; die manuelle Erfassung enthält zudem
eine optionale Termin-Auswahl. Die Werte werden in die
ASDS-Zeitleistung geschrieben und bei Bedarf mit dem Termin verknüpft.
**Keine Serverdatei wurde mit dem Android-Build geändert oder installiert.**
Das Vorhandensein dieser Felder im Quellpaket ist kein Nachweis ihres
Vorhandenseins auf dem Produktivserver.

## Automatisierte Tests
- `node tools/test_offline_time_202.cjs`: CHF-Rundung, Eingabegrenzen,
  Kundenzuordnung, Terminfilter, Feldnamen, tatsächlicher
  Offline-Asset-Ladepfad und vorhandene Queue-Anbindung.
- `./gradlew :app:testDebugUnitTest`: `R1AmountsTest` sowie
  `LegacyOfflineTimeSyncTest` (302, 200/Fehler, 409, 500,
  Legacy- und R1-Bestätigung, Capability-Sperre).
- `./gradlew :app:assembleDebug :app:assembleRelease`.
- `./gradlew :app:assembleDebugAndroidTest`: Kompilierung des
  Room-Gerätetests, kein Ersatz für dessen Ausführung.

## Noch auf isoliertem Testgerät und Testserver nachzuweisen
- [ ] Signierte installierte 2.0.1 **ohne Deinstallation** mit
      signaturgleicher 2.0.2 aktualisieren, `versionCode=202`.
- [ ] Bereits vorhandene Offline-Entwürfe, verschlüsselte Queue,
      Belege und R1-Room-Daten vor/nach Update identisch; kein Datenverlust.
- [ ] Offline Arbeitszeit erfassen: 3.35 km, CHF 0.70,
      Kundenzuordnung, Termin; Anzeige CHF 2.35.
- [ ] App beenden, offline neu starten, gespeicherte Queue kontrollieren.
- [ ] Mit alter Serverkonfiguration kein Upload der 3.35 km;
      Eintrag bleibt `waiting_server`.
- [ ] Nach Testserver-Upgrade und Konfigurationsrefresh
      **genau ein** neuer Datensatz mit `km=3.35`,
      `km_rate=0.70` und Terminverknüpfung.
- [ ] Künstlicher Responseverlust nach Servercommit:
      Wiederholung derselben `client_id` legt keine zweite Leistung an.
- [ ] 302/401/409/429/500, Monatsabschluss, ungültiger Termin,
      Zugriffssperre und Datenfreigabe prüfen.
- [ ] Online `time.php`: manuell sowie Timer stoppen mit km/Ansatz;
      Erfassung und spätere Kilometerposition auf Staging-Rechnung.
- [ ] Keine doppelte Fahrt und keine doppelte Kilometerposition
      aus `km`/ `billable_km`.
- [ ] Bisherige Zeiterfassung, Spesen, Fahrt, Protokoll, Belege,
      Biometrie und R1-Synchronisation weiterhin funktionsfähig.

**Freigabegrenze:** Kein Signierschlüssel im Repository. APK bleibt
unsigniert für die lokale Release-Signierung in Android Studio.
Kein Produktivserver-Update, kein Play-Store-Release.

## Android Studio / Update
Der Branch bleibt:
`develop/android-studio-2.0.1-maintainable`.

In bereits geöffnetem Android Studio im Branch `git pull` ausführen,
Gradle synchronisieren, Debug auf einem **separaten** Testgerät prüfen.
Für ein Update der bestehenden Installation in
**Build → Generate Signed App Bundle / APK** den bisherigen Release-Keystore
mit exakt gleichem Zertifikat verwenden. Die alte 2.0.1 nie für einen
Test deinstallieren.
