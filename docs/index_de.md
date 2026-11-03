---
title: Ingest in MyCoRe
identifier: intranda_step_mycore_ingest
description: Step Plugin für den automatisierten Ingest von Vorgängen in MyCoRe
published: true
keywords:
    - Goobi workflow
    - Plugin
    - Step Plugin
---

## Einführung
Diese Dokumentation erläutert das Plugin für den automatisierten Ingest in ein MyCoRe Repository.

## Installation
Um das Plugin nutzen zu können, müssen folgende Dateien installiert werden:

```bash
/opt/digiverso/goobi/plugins/step/plugin-step-mycore-ingest-base.jar
/opt/digiverso/goobi/config/plugin_intranda_step_mycore_ingest.xml
```

Nach der Installation des Plugins kann dieses innerhalb des Workflows für die jeweiligen Arbeitsschritte ausgewählt und somit automatisch ausgeführt werden. Ein Workflow könnte dabei beispielhaft wie folgt aussehen:

![Beispielhafter Aufbau eines Workflows](screen1_de.png)

Für die Verwendung des Plugins muss dieses in einem Arbeitsschritt ausgewählt sein:

![Konfiguration des Arbeitsschritts für die Nutzung des Plugins](screen2_de.png)


## Überblick und Funktionsweise
Wenn der Arbeitsschritt mit diesem Plugin aktiviert wird, startet dieses den Ingest in MyCoRe. Dafür wird zunächst eine Transformation der METS-Datei durchgeführt, indem eine METS-Datei exportiert und dann mittels einer xsl-Datei von einer konfigurierbaren URL transformiert wird. Mit der so erzeugten XML-Datei wird anschließend in MyCoRe ein Band angelegt und darin ein Derivat erzeugt. Innerhalb des Derivats werden anschließend die Mediendateien, die Volltextdateien sowie die METS-Datei zusammen mit der Anchordatei hochgeladen. 

Abschließend werden die Checksummen und Größen der Dateien ermittelt und verglichen und als Ingest-Quittung innerhalb des Vorgangsjournals abgelegt.


## Konfiguration
Die Konfiguration des Plugins erfolgt in der Datei `plugin_intranda_step_mycore_ingest.xml` wie hier aufgezeigt:

{{CONFIG_CONTENT}}

{{CONFIG_DESCRIPTION_PROJECT_STEP}}

Parameter               | Erläuterung
------------------------|------------------------------------
`xslt-url`              | Angabe der URL, unter der eine xsl-Datei für die Transformation erreicht werden kann. Eine Beispieldatei befindet sich innerhalb des `docs`-Verzeichnisses des Plugins.
`mycore-api`            | URL für die MyCoRe-API
`mycore-login`          | Angabe des Login Namens
`mycore-password`       | Angabe des Login Passworts
`max-tries`             | Angabe über die maximale Anzahl an Versuchen, die für den Ingest genutzt werden soll
`debug-mets-before-xslt` | Optional. Vollständiger Dateipfad, unter dem die exportierte METS-Datei vor der XSLT-Transformation gespeichert wird.
`debug-mets-after-xslt` | Optional. Vollständiger Dateipfad, unter dem das Ergebnis der XSLT-Transformation gespeichert wird, genau so, wie es an MyCoRe gesendet wird.

### Debug-Dateien
Zur Fehlersuche kann das Plugin die exportierte METS-Datei und das Ergebnis der XSLT-Transformation speichern. So lässt sich feststellen, ob ein Problem aus dem METS-Export oder aus der Transformation stammt. Beide Parameter können unabhängig voneinander genutzt werden; fehlt ein Parameter oder ist er leer, wird die Datei nicht gespeichert.

- Der Wert ist ein vollständiger, absoluter Pfad inklusive Dateiname, kein Ordner. Fehlende Ordner werden angelegt.
- Goobi-Variablen können verwendet werden, sodass sowohl ein globaler Ordner als auch ein Ordner pro Vorgang möglich ist, z. B. `{processpath}/debug/mets.xml` oder `/opt/digiverso/debug/{processtitle}_mets.xml`. In einem gemeinsamen Ordner sollte `{processtitle}` oder `{processid}` im Dateinamen stehen, damit sich Vorgänge nicht gegenseitig überschreiben.
- Ohne Datumsvariable überschreibt jeder Lauf die vorherige Datei. Mit `{datetime.yyyyMMdd-HHmmss}` entsteht bei jedem Lauf eine neue Datei; beide Dateien eines Laufs erhalten denselben Zeitstempel. Das Datumsmuster darf weder `)` noch `}` enthalten.
- Wenn konfiguriert, werden die Dateien bei jedem Lauf gespeichert. Die METS-Datei wird direkt nach dem Export gespeichert, das Transformationsergebnis bevor es an MyCoRe gesendet wird, sodass beide auch dann vorliegen, wenn ein späterer Schritt fehlschlägt.
- Alte Debug-Dateien werden nicht automatisch gelöscht.
- Kann eine Debug-Datei nicht gespeichert werden, wird nur eine Warnung protokolliert; der Ingest läuft weiter.
