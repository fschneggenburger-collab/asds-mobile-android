# ASDS Mobile Android 2.1.1 – Beschreibungs-Textbausteine

Die Online-Zeiterfassung zeigt nach Installation von ASDS Suite **10.41** und ASDS Mobile-Server **2.1.5** gemeinsame und persönliche Textbausteine mit Suche, Kategorien, Favoriten, Mehrfach-Einfügen und persönlicher Schnellvorlage.

## Offline-Modus
- Offline-Stammdaten liefern `description_templates` und `description_categories` nur für den angemeldeten, gekoppelten Benutzer.
- Die lokale Offline-Zeiterfassung filtert diese gespeicherten Vorlagen nach Suchbegriff, Kategorie und Favoriten, und fügt den ausgewählten Satz ins editierbare Beschreibungsfeld ein. Mehrere Bausteine können nacheinander eingefügt werden.
- Aus einer ausgefüllten Offline-Beschreibung lässt sich eine neue **persönliche** Vorlage in der **verschlüsselten Offline-Warteschlange** vormerken (`kind=description_template`). Sie wird beim nächsten erfolgreichen Synchronisieren auf dem ASDS Mobile-Server 2.1.5 gespeichert; bis dahin bleibt sie in der geöffneten Offline-Seite auswählbar. Eine erneute App-/Stammdatenaktualisierung ist nötig, damit sie geräteübergreifend sichtbar ist.
- Bestehende Kilometer-, Termin- und Zeiterfassungsfelder bleiben unverändert. Die Synchronisation nutzt die vorhandene gerätegebundene Autorisierung und eine idempotente client_id.
- Bereits lokal gespeicherte Vorlagen werden durch die Offline-Stammdatenaktualisierung geladen; die App benötigt einmalig eine Verbindung für die Erstbefüllung.
- Keine Schlüssel/Signaturdateien im Repository.

## Versionierung und Signierung
Version **2.1.1**, versionCode **211**. Für ein APK in Android Studio `main` aktualisieren und mit der bisherigen privaten Signatur signieren. Kein automatisches Produktiv-Rollout.

## Abhängigkeiten und offene Abnahme
ASDS Suite 10.41 → DB-Update → Mobile-Server 2.1.5 → Android 2.1.1. Kotlin und Offline-HTML wurden nicht auf einem physischen Androidgerät oder mit produktivem Dolibarr/SQL im Rahmen der isolierten Tests geprüft.