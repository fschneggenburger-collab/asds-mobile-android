# ASDS Android 2.1.0 – gemeinsames Fahrten-Cockpit

Die Android-App verwendet dieselbe serverseitige Fahrten-Cockpit-Oberfläche wie ASDS Mobility. Navigation **Fahrten** zeigt die vom ASDS Mobile Server 2.1.0 gelieferte Ansicht. Neue Schnellaktionen öffnen das Fahrten-Cockpit und den Abschnitt **Zusammenfassen**.

## Abhängigkeiten
- ASDS Mobility 1.2.0 mit zentraler Prüf- und Zusammenfassungslogik
- ASDS Mobile Server 2.1.0 mit serverbasierter WebView-Ansicht und gültiger Gerätepaarung
- ASDS Suite 10.40 für den ERP-Fahrtenbuch-Einstieg

## Sicherheit
- Verbindliche Zusammenfassung nur nach Vorschau und serverseitiger Neubewertung in einer Transaktion
- Originale bleiben erhalten; vor ERP-Übernahme ist Aufhebung möglich
- Keine echte Zusammenfassung im Offline-Modus: Bei fehlender Verbindung muss die App bis zur späteren serverseitigen Bestätigung warten. Offline erfasste Einzel-Fahrten bleiben über die bestehende Offline-Warteschlange möglich
- Android 2.0.2 bleibt die produktiv installierte Version, bis 2.1.0 signiert und auf einem Testgerät geprüft ist
- Keine Signierungsschlüssel im Repository

## Entwicklungsstatus
Kotlin und Android Gradle wurden für 2.1.0 angepasst. Browser-/Datenbank- und Android-Ende-zu-Ende-Tests sind vor Produktivfreigabe erforderlich.
