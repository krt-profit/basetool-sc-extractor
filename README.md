# Basetool Blueprint Extractor

Eine kleine **Kotlin-Desktop-App** (Compose for Desktop), die aus Star-Citizen-
`Game.log`-Dateien ausliest, **welche Blueprints ein Spieler erhalten hat**, und
das Ergebnis als JSON schreibt. Inspiriert vom „SCMDB Log Watcher", aber bewusst
auf **Blueprints fokussiert** — Missionsdaten werden nicht ausgewertet.

<table>
<tr>
<td width="120" align="center">
<img width="88" alt="Made by the Community" src="docs/img/MadeByTheCommunity_White.png#gh-light-mode-only"><img width="88" alt="Made by the Community" src="docs/img/MadeByTheCommunity_Black.png#gh-dark-mode-only">
</td>
<td>
<b>Inoffizielles Star-Citizen-Fan-Tool</b> — nicht mit der Cloud-Imperium-Unternehmensgruppe affiliiert.<br>
Star Citizen®, Roberts Space Industries® and Cloud Imperium® are registered trademarks of Cloud Imperium Rights LLC
</td>
</tr>
</table>

Die App bringt eine **Installationsroutine** (MSI) mit und lässt sich wie jedes
normale Windows-Programm wieder **deinstallieren** (Einträge in „Apps & Features",
Startmenü, Desktop-Verknüpfung).

---

## Für Endnutzer

### Installieren

1. `Basetool Blueprint Extractor-1.0.0.msi` doppelklicken.
2. Dem Installations-Assistenten folgen (es lässt sich ein Installationsordner
   wählen). Es werden **keine Administratorrechte** benötigt — die Installation
   erfolgt pro Benutzer.
3. Danach gibt es einen Startmenü-Eintrag unter **Basetool** und eine
   Desktop-Verknüpfung.

> Ein separates Java/JRE muss **nicht** installiert werden — die Laufzeit ist im
> Installer enthalten.

### Benutzen

1. App starten.
2. **Star-Citizen-Channel-Ordner** wählen — z. B.
   `C:\Program Files\Roberts Space Industries\StarCitizen\LIVE`. Ausgelesen werden
   die `Game.log` in diesem Ordner **und** alle Logs im Unterordner `logbackups`
   (`Game Build(...).log`). Existiert der Standard-LIVE-Pfad auf deinem Rechner,
   ist das Feld bereits vorausgefüllt.
3. **Ausgabe-JSON (Ziel)** wählen — wohin die JSON-Datei geschrieben werden soll.
4. Auf **Blueprints extrahieren** klicken.

Nach dem Lauf zeigt die App eine Zusammenfassung (erkannte Spieler, Blueprints
nach Kategorie, die zuletzt erhaltenen Blueprints) und schreibt die vollständige
Liste in die gewählte JSON-Datei.

### Deinstallieren

Wie jedes Windows-Programm:
**Einstellungen → Apps → Installierte Apps → „Basetool Blueprint Extractor" →
Deinstallieren** (oder klassisch über *Systemsteuerung → Programme und Features*).

**Restlose Entfernung — verifiziert.** Ein Install→Deinstall-Testzyklus bestätigt,
dass die Deinstallation **alles** entfernt:

| Artefakt | nach Deinstallation |
|---|---|
| Programmordner `%LOCALAPPDATA%\Basetool Blueprint Extractor\` (gebündelte JRE, ~515 Dateien) | entfernt |
| Startmenü-Gruppe „Basetool" inkl. Verknüpfung | entfernt |
| Desktop-Verknüpfung | entfernt |
| „Apps & Features"-/Registry-Eintrag | entfernt |

Das funktioniert **restlos**, weil die App bewusst **nichts** in ihren eigenen
Installationsordner schreibt (keine `config.json`, keine `logs/` — anders als das
Python-Vorbild). Solche zur Laufzeit erzeugten Fremddateien sind der übliche Grund,
warum sonst ein leerer Programmordner zurückbleibt.

> Deine **exportierten JSON-Dateien** liegen am selbst gewählten Ort (Standard:
> `Dokumente\blueprints.json`) und werden bei der Deinstallation **absichtlich nicht**
> gelöscht — das sind deine Daten, kein Programm-Rest.

---

## Die JSON-Ausgabe

```jsonc
{
  "schemaVersion": 1,
  "tool": "Basetool Blueprint Extractor",
  "toolVersion": "1.0.0",
  "generatedAt": "2026-05-30T21:39:45Z",   // UTC, wann der Export erzeugt wurde
  "sourceFolder": "…\\StarCitizen\\LIVE",   // der Channel-Ordner
  "logFilesScanned": 424,                    // Game.log + logbackups\*.log
  "blueprintCount": 179,                     // Gesamtzahl erhaltener Blueprints
  "players": [
    {
      "handle": "greluc",                    // Spielername (aus Login-Zeilen)
      "blueprintCount": 179
    }
  ],
  "blueprints": [
    {
      "productName": "Yubarev \"Mirage\" Pistol", // exakter Item-Name (inkl. Anführungszeichen)
      "category": "Weapon",                       // abgeleitete Kategorie (s. u.)
      "receivedAt": "2026-03-26T16:49:31.050Z",   // Zeitpunkt des Erhalts (UTC)
      "player": "greluc",                         // Empfänger (aus der Quelldatei)
      "notificationId": 19,                       // In-Game-Notification-Index
      "queueSize": 2,                             // gemeldete Notification-Queue-Größe
      "gameBuild": "11518367",                    // SC-Build-Nr. (aus dem Dateinamen)
      "sourceFile": "Game Build(11518367) 26 Mar 26 (17 24 58).log"
    }
    // … chronologisch sortiert …
  ]
}
```

**Kategorien** (`category`) werden heuristisch aus dem Namen abgeleitet — der
Log selbst nennt keine Kategorie:
`MiningTool` · `Ammo` (Magazine/Batterien) · `Armor` (Helmet/Core/Arms/Legs/…) ·
`Weapon` (Pistol/Rifle/Shotgun/…) · `Other`.

---

## Wie das Auslesen funktioniert

Star Citizen schreibt beim Erhalt eines Blueprints eine Notification-Zeile:

```
<2026-03-26T16:49:31.050Z> [Notice] <SHUDEvent_OnNotification> Added notification
  "Received Blueprint: Yubarev "Mirage" Pistol: " [19] to queue. New queue size: 2,
  MissionId: [00000000-0000-0000-0000-000000000000], ObjectiveId: [] [...]
```

Der Parser ([`BlueprintParser.kt`](src/main/kotlin/com/basetool/bpextractor/BlueprintParser.kt))
behandelt die realen Eigenheiten dieser Zeilen:

- **Anker auf `Added notification`** — jede Blueprint-Meldung erscheint mehrfach
  im Log (die ursprüngliche Notification, eine Queue-Echo-Zeile und mehrere
  `UpdateNotificationItem`-Folgezeilen mit `Next`/`StartFade`/`Remove`). Nur die
  `Added notification`-Zeile wird gezählt, damit jeder Blueprint **genau einmal**
  gezählt wird (sonst ~6-fach).
- **Namen mit Anführungszeichen** (`Yubarev "Mirage" Pistol`), **Klammern**
  (`Yubarev Pistol Battery (10 cap)`), **Slashes** (`Sth/2/C Cirrus`) und
  **Bindestrichen** (`ADP-mk4 Core Woodland`) werden korrekt erfasst. Der Name
  endet stabil am `: " [<id>]`-Trenner.
- **Spielername** kommt aus den Login-Zeilen derselben Datei
  (`User Login Success - Handle[…]` bzw. die Charakter-Status-Zeile mit `geid`
  und `accountId`) — die `MissionId` auf der Blueprint-Zeile ist immer `0000…`
  und daher nutzlos.
- **Build-Nummer** stammt aus dem Dateinamen (`Game Build(11518367) …`).

Große Logs (teils > 30 MB) werden **zeilenweise gestreamt**, nie komplett in den
Speicher geladen.

---

## Selbst bauen (für Entwickler)

**Voraussetzung:** JDK 25 (z. B. Azul Zulu). Gradle wird über den mitgelieferten
Wrapper bereitgestellt.

```powershell
# Tests ausführen
.\gradlew.bat test

# App starten (GUI)
.\gradlew.bat run

# Headless / Skript-Modus
.\gradlew.bat run --args="C:\Program Files\Roberts Space Industries\StarCitizen\LIVE C:\Pfad\zu\out.json"

# Windows-Installer (MSI) bauen — IMMER über dieses Skript (s. WiX-Hinweis unten)
.\package-msi.ps1
```

Die fertige MSI liegt danach unter `dist\Basetool Blueprint Extractor-1.0.0.msi`
(das Skript kopiert sie dorthin; das Gradle-Original liegt unter
`build\compose\binaries\main\msi\`).

### Hinweis zu WiX (MSI-Erzeugung) — wichtig unter JDK 25

`packageMsi` nutzt `jpackage`, und das hat einen bekannten Bug
([JDK-8356592](https://bugs.openjdk.org/browse/JDK-8356592)): mit WiX **4/5/6/7**
bricht es mit Fehlercode **144** ab. Zuverlässig läuft nur **WiX 3.x**
(`candle.exe`/`light.exe`). Das Compose-Plugin lädt WiX 3 automatisch — **aber**
sobald ein neueres `wix.exe` (z. B. ein installiertes „WiX Toolset v6/v7") auf dem
`PATH` liegt, bevorzugt jpackage dieses und scheitert.

Deshalb die MSI **immer** über das mitgelieferte Skript bauen:

```powershell
.\package-msi.ps1
```

Es nimmt alle „WiX Toolset"-Verzeichnisse (v4+) aus dem PATH des Builds und nutzt
WiX 3.14 (`tools\wix3` bzw. den Auto-Download des Plugins). Am System wird **nichts**
geändert; die fertige MSI landet in `dist\`.

### Installer-Verhalten anpassen

In [`build.gradle.kts`](build.gradle.kts) unter `windows { … }`:

| Option | Wirkung |
|---|---|
| `perUserInstall = true` | Installation ohne Admin, pro Benutzer in „Apps & Features" |
| `dirChooser = true` | Schritt zur Auswahl des Installationsordners |
| `menu = true` / `menuGroup` | Startmenü-Eintrag |
| `shortcut = true` | Desktop-Verknüpfung |
| `upgradeUuid` | stabile ID, damit neue Versionen die alte ersetzen |
| `iconFile` | eigenes Icon (`src/main/resources/app.ico` ablegen) |

> Die MSI ist ~60 MB. Gebündelt wird eine **schlanke** JDK-25-Laufzeit — nur die
> wirklich benötigten Module: `modules("java.instrument", "jdk.unsupported")` plus die
> vom Compose-Plugin automatisch erkannten (`java.desktop` etc.), ermittelt via
> `gradlew suggestRuntimeModules`. Der Nutzer braucht trotzdem kein eigenes Java.
> (`jvmArgs += "--enable-native-access=ALL-UNNAMED"` unterdrückt die JDK-25-
> „native access"-Warnungen, die Skikos `System.load()` sonst auf stderr schreibt.)

---

## Projektstruktur

```
basetool-bp-extractor/
├── build.gradle.kts                  # Build + Compose-/MSI-Konfiguration
├── settings.gradle.kts
├── gradle.properties
├── gradlew(.bat)                     # Gradle-Wrapper (9.5.1)
├── package-msi.ps1                   # MSI-Build (umgeht den jpackage/WiX-Bug)
├── src/main/kotlin/com/basetool/bpextractor/
│   ├── Main.kt                       # Compose-GUI + CLI-Einstieg
│   ├── BlueprintParser.kt            # Zeilen-Parsing (Kern)
│   ├── BlueprintExtractor.kt         # Ordner-Scan, Aggregation, JSON
│   ├── ui/Theme.kt                   # KRT-Theme (Farben, Fonts, Typo, Shapes)
│   ├── ui/KrtComponents.kt           # HUD-Box, CTA-/Ghost-Buttons, Checkbox …
│   ├── ui/WindowChrome.kt            # undekorierte Titelleiste + Fenster-Buttons
│   └── model/Models.kt               # JSON-Datenmodelle
├── src/main/resources/               # Fonts (Audiowide/Lato), app.ico,
│                                     #   honeycomb-bg.svg, icons/krt-icon.png
├── src/test/kotlin/…                 # Unit-Tests
├── src/test/resources/sample.log     # Test-Fixture (Edge-Cases)
└── game-log/                         # Beispiel-Logs (Eingabe)
```

## Design

Die GUI folgt dem **„Das Kartell" / KRT-Design-System** (Quelle: Claude-Skill
`das-kartell-design`): dunkles Sci-Fi-„HUD", Hausorange `#E77E23` auf Schwarz,
Display-Schrift **Audiowide** (UPPERCASE) + Body **Lato**, durchweg scharfe
Ecken mit diagonalen HUD-Eckwinkeln, Orange-Bloom statt weicher Schatten. Umgesetzt
als Compose-Material3-Theme (`ui/Theme.kt`, `ui/KrtComponents.kt`) mit strenger
Action-Hierarchie: genau **eine** gefüllte Orange-CTA pro Kontext („Blueprints
extrahieren"), Sekundäraktionen als Ghost-Buttons, Labels neutral-grau.

Das **KRT-Logo** dient als App-/Fenster-Icon (`app.ico` für Installer/Exe,
`icons/krt-icon.png` fürs Fenster), und ein **dezenter Honeycomb-Hintergrund**
(`honeycomb-bg.svg` — orange Hexagone bei 10 % Deckkraft) liegt als Textur hinter
dem Inhalt. Logo + Honeycomb (und **Lato**) stammen aus dem Skill
`das-kartell-design/assets/`. Die Display-Schrift **Audiowide** (OFL/Google Fonts,
Lizenztext in `fonts/Audiowide-OFL.txt`) ersetzt die markenseitig vorgesehene,
**kommerzielle** Ethnocentric durch eine frei redistribuierbare, optisch
verwandte Variante — bewusst gewählt, damit das öffentliche Repo keine
lizenzpflichtige Schrift mitliefert.

Das Fenster ist **undekoriert** (kein weißer OS-Rahmen): eine eigene dunkle
Titelleiste (`ui/WindowChrome.kt`) trägt Logo + Titel und eigene Minimieren-/
Maximieren-/Schließen-Buttons, mit scharfen Ecken, oranger Akzentlinie, dünnem
HUD-Rahmen und einer Resize-Ecke unten rechts.

## Lizenz

Dieses Programm ist **freie Software** unter der **GNU General Public License,
Version 3 oder (nach Wahl) später** (GPL-3.0-or-later). Den vollständigen Lizenztext
findest du in [`LICENSE`](LICENSE).

```
Copyright (C) 2026 Basetool

Dieses Programm ist freie Software: Du kannst es weitergeben und/oder verändern,
solange du dich an die Bedingungen der GNU General Public License hältst. Es wird
in der Hoffnung verteilt, dass es nützlich ist, jedoch OHNE JEDE GEWÄHRLEISTUNG.
```

### Drittanbieter-Komponenten

| Komponente | Lizenz |
|---|---|
| Kotlin, Compose Multiplatform, Material 3, Skiko, kotlinx-serialization/-coroutines | Apache-2.0 |
| Skia (via Skiko) | BSD-3-Clause |
| Gebündelte Java-Laufzeit (OpenJDK 25) | GPLv2 **mit Classpath Exception** |
| Schriften **Audiowide** & **Lato** | SIL Open Font License 1.1 |

Die OFL-Lizenztexte der Schriften liegen unter
[`src/main/resources/fonts/`](src/main/resources/fonts/) (`Audiowide-OFL.txt`,
`Lato-OFL.txt`). Die **Classpath Exception** der gebündelten JRE erlaubt die Weitergabe
zusammen mit diesem (GPL-)Programm, ohne dass die JRE selbst dadurch ihre Lizenz ändert;
ihre Notices liegen im Installationspaket unter `runtime/legal/`.

### Marken

Das **KRT-/„Das Kartell"-Logo** und zugehörige Markenzeichen sind Eigentum ihrer
Inhaber. Die GPL deckt den **Quellcode** dieses Programms ab, **nicht** die Marken-
und Logo-Assets — diese werden nicht zur freien Weiterverwendung lizenziert.

### Star Citizen

Dies ist ein **inoffizielles Star-Citizen-Fan-Tool** und steht in keiner Verbindung zur
Cloud-Imperium-Unternehmensgruppe. Es verwendet das **„Made by the Community"-Logo** und
den Marken-Hinweis gemäß dem offiziellen **Star Citizen Fankit** (Fankit Agreement, Fan
Style Guide und RSI Terms of Service). Es werden **keine** Star-Citizen-Spieldaten oder
-Assets mitgeliefert; die ausgelesenen `Game.log`-Dateien und die exportierten JSON-Daten
gehören dir.

> Star Citizen®, Roberts Space Industries® and Cloud Imperium® are registered trademarks of Cloud Imperium Rights LLC

Fankit-Assets wie das „Made by the Community"-Logo sind Eigentum von Cloud Imperium Rights
LLC / Cloud Imperium Rights Ltd. und werden unter den Fankit-Bedingungen verwendet — die
GPL dieses Projekts erstreckt sich **nicht** auf diese Marken und Assets.
