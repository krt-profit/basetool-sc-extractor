package com.basetool.bpextractor.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/** The two UI languages: German is the default, English has full parity (design spec §6). */
enum class Lang { DE, EN }

/**
 * Every user-facing UI string of the app, one property (or formatter) per string. The design spec
 * (DESIGN_SC_EXTRACTOR.md §6) demands German default with full English parity and a title-bar
 * DE/EN toggle, so strings live in this lightweight catalogue instead of being hardcoded at the
 * call sites. Parameterised messages are lambdas so call sites stay type-safe without a template
 * engine. Brand names (app title, workflow product names) stay identical across languages.
 */
class Strings(
    // --- tabs / shell ---
    val tabStart: String,
    val tabBlueprints: String,
    val tabRefinery: String,

    // --- start screen (launcher) ---
    val startTitle: String,
    val startSubtitle: String,
    val startOpen: String,
    val bpCardTitle: String,
    val bpCardDesc: String,
    val bpCardInputHint: String,
    val rfCardTitle: String,
    val rfCardDesc: String,
    val rfCardInputHint: String,
    val unofficialChip: String,

    // --- blueprint workflow ---
    val bpGreetingTitle: String,
    val bpGreetingSubtitle: String,
    val bpStatusInitial: String,
    val bpLabelChannelFolder: String,
    val bpPlaceholderChannel: String,
    val bpLabelOutputJson: String,
    val bpPlaceholderOutput: String,
    val browse: String,
    val bpPickerChannelTitle: String,
    val bpPickerChannelConfirm: String,
    val bpPickerSaveTitle: String,
    val bpPickerSaveConfirm: String,
    val bpCta: String,
    val bpHintReadsLogs: String,
    val bpHintFolderMissing: String,
    val bpHintWrongFolder: String,
    val bpHintValidFolder: (String) -> String,
    val bpHotfixNote: String,
    val bpErrSelectChannel: String,
    val bpErrFolderNotFound: (String) -> String,
    val bpErrSelectOutput: String,
    val bpStatusFixFields: String,
    val bpStatusSearching: String,
    val bpStatusEvaluating: String,
    val bpStatusProcessing: (Int, Int, String) -> String,
    val bpStatusNoLogs: String,
    val bpToastNoLogsTitle: String,
    val bpToastNoLogsBody: String,
    val bpStatusDone: (Int, Int, String) -> String,
    val bpToastDoneTitle: String,
    val bpToastDoneBody: (Int) -> String,
    val bpStatusError: (String) -> String,
    val bpToastErrorTitle: String,
    val unknownError: String,
    val bpResultTitle: String,
    val bpShowInFolder: String,
    val bpOpenJson: String,
    val cannotOpen: (String, String) -> String,
    val bpSummaryPlayers: String,
    val bpSummaryNoPlayer: String,
    val bpSummaryByCategory: String,
    val bpSummaryRecent: String,

    // --- refinery workflow (steps; screens fill in over Phase 3) ---
    val rfSteps: List<String>,
    val rfPlaceholderTitle: String,
    val rfPlaceholderBody: String,
)

/** German catalogue — the default language. */
val StringsDe = Strings(
    tabStart = "Start",
    tabBlueprints = "Blueprints",
    tabRefinery = "Refinery",

    startTitle = "Basetool SC Extractor",
    startSubtitle = "Liest Star-Citizen-Daten lokal aus und erzeugt JSON-Dateien für das Basetool — nichts verlässt deinen Rechner.",
    startOpen = "Öffnen →",
    bpCardTitle = "Blueprints",
    bpCardDesc = "Liest die erhaltenen Blueprints aus den Game.log-Dateien aus und schreibt sie als JSON.",
    bpCardInputHint = "Eingabe: Star-Citizen-Channel-Ordner (Game.log + logbackups)",
    rfCardTitle = "Refinery",
    rfCardDesc = "Liest Raffinerie-Auftragsdaten aus SETUP-Screenshots per lokalem KI-Modell (Ollama) aus und erzeugt eine RefineryExtract-JSON.",
    rfCardInputHint = "Eingabe: Screenshots eines Auftrags (1 Ordner = 1 Auftrag)",
    unofficialChip = "Inoffizielles Fan-Tool",

    bpGreetingTitle = "Blueprint-Extraktion",
    bpGreetingSubtitle = "Liest die erhaltenen Blueprints aus Star-Citizen-Game.log-Dateien aus und schreibt sie als JSON.",
    bpStatusInitial = "Wähle den Star-Citizen-Channel-Ordner (z. B. …\\StarCitizen\\LIVE) und einen Ziel-Pfad für die JSON.",
    bpLabelChannelFolder = "Star-Citizen-Channel-Ordner",
    bpPlaceholderChannel = "z. B. C:\\Program Files\\Roberts Space Industries\\StarCitizen\\LIVE",
    bpLabelOutputJson = "Ausgabe-JSON (Ziel)",
    bpPlaceholderOutput = "z. B. …\\Dokumente\\blueprints.json",
    browse = "Durchsuchen…",
    bpPickerChannelTitle = "Channel-Ordner wählen",
    bpPickerChannelConfirm = "Diesen Ordner wählen",
    bpPickerSaveTitle = "JSON-Ausgabedatei wählen",
    bpPickerSaveConfirm = "Speichern",
    bpCta = "Blueprints extrahieren",
    bpHintReadsLogs = "Liest die Game.log in diesem Ordner und alle Logs im Unterordner „logbackups\".",
    bpHintFolderMissing = "Ordner existiert nicht.",
    bpHintWrongFolder = "Ordner gefunden, aber keine Game.log/logbackups — evtl. der falsche Ordner.",
    bpHintValidFolder = { found -> "Gültiger Channel-Ordner ($found erkannt)." },
    bpHotfixNote = "HOTFIX-Ordner daneben gefunden — dessen Logs werden zusätzlich ausgelesen.",
    bpErrSelectChannel = "Bitte einen Channel-Ordner auswählen.",
    bpErrFolderNotFound = { path -> "Ordner nicht gefunden: $path" },
    bpErrSelectOutput = "Bitte einen Ziel-Pfad für die JSON angeben.",
    bpStatusFixFields = "Bitte die markierten Felder korrigieren.",
    bpStatusSearching = "Suche Log-Dateien…",
    bpStatusEvaluating = "Werte aus…",
    bpStatusProcessing = { done, total, label -> "Verarbeite Datei $done/$total: $label" },
    bpStatusNoLogs = "Keine Game.log und kein „logbackups\"-Ordner im Channel-Ordner gefunden.",
    bpToastNoLogsTitle = "Keine Logs gefunden",
    bpToastNoLogsBody = "Im Channel-Ordner wurde keine Game.log gefunden.",
    bpStatusDone = { count, files, path -> "Fertig: $count Blueprint(s) aus $files Datei(en) geschrieben nach $path" },
    bpToastDoneTitle = "Fertig",
    bpToastDoneBody = { count -> "$count Blueprint(s) gespeichert." },
    bpStatusError = { msg -> "Fehler: $msg" },
    bpToastErrorTitle = "Fehler",
    unknownError = "Unbekannter Fehler",
    bpResultTitle = "Ergebnis",
    bpShowInFolder = "Im Ordner anzeigen",
    bpOpenJson = "JSON öffnen",
    cannotOpen = { name, msg -> "Konnte „$name\" nicht öffnen: $msg" },
    bpSummaryPlayers = "Spieler:",
    bpSummaryNoPlayer = "(keiner erkannt)",
    bpSummaryByCategory = "Blueprints nach Kategorie:",
    bpSummaryRecent = "Letzte erhaltene Blueprints:",

    rfSteps = listOf("Vorprüfung", "Bilder", "Extraktion", "Review", "Export"),
    rfPlaceholderTitle = "Refinery-Extraktion",
    rfPlaceholderBody = "Dieser Workflow wird gerade gebaut (Phase 3 von Epic #439).",
)

/** English catalogue — full parity with [StringsDe]. */
val StringsEn = Strings(
    tabStart = "Start",
    tabBlueprints = "Blueprints",
    tabRefinery = "Refinery",

    startTitle = "Basetool SC Extractor",
    startSubtitle = "Extracts Star Citizen data locally and produces JSON files for the basetool — nothing leaves your machine.",
    startOpen = "Open →",
    bpCardTitle = "Blueprints",
    bpCardDesc = "Reads the received blueprints from the Game.log files and writes them as JSON.",
    bpCardInputHint = "Input: Star Citizen channel folder (Game.log + logbackups)",
    rfCardTitle = "Refinery",
    rfCardDesc = "Reads refinery work-order data from SETUP screenshots via a local AI model (Ollama) and produces a RefineryExtract JSON.",
    rfCardInputHint = "Input: screenshots of one order (1 folder = 1 order)",
    unofficialChip = "Unofficial fan tool",

    bpGreetingTitle = "Blueprint extraction",
    bpGreetingSubtitle = "Reads the received blueprints from Star Citizen Game.log files and writes them as JSON.",
    bpStatusInitial = "Choose the Star Citizen channel folder (e.g. …\\StarCitizen\\LIVE) and a target path for the JSON.",
    bpLabelChannelFolder = "Star Citizen channel folder",
    bpPlaceholderChannel = "e.g. C:\\Program Files\\Roberts Space Industries\\StarCitizen\\LIVE",
    bpLabelOutputJson = "Output JSON (target)",
    bpPlaceholderOutput = "e.g. …\\Documents\\blueprints.json",
    browse = "Browse…",
    bpPickerChannelTitle = "Choose channel folder",
    bpPickerChannelConfirm = "Select this folder",
    bpPickerSaveTitle = "Choose output JSON file",
    bpPickerSaveConfirm = "Save",
    bpCta = "Extract blueprints",
    bpHintReadsLogs = "Reads the Game.log in this folder and every log in its \"logbackups\" subfolder.",
    bpHintFolderMissing = "Folder does not exist.",
    bpHintWrongFolder = "Folder found, but no Game.log/logbackups — possibly the wrong folder.",
    bpHintValidFolder = { found -> "Valid channel folder ($found detected)." },
    bpHotfixNote = "HOTFIX folder found next to it — its logs are read as well.",
    bpErrSelectChannel = "Please select a channel folder.",
    bpErrFolderNotFound = { path -> "Folder not found: $path" },
    bpErrSelectOutput = "Please provide a target path for the JSON.",
    bpStatusFixFields = "Please correct the highlighted fields.",
    bpStatusSearching = "Searching log files…",
    bpStatusEvaluating = "Evaluating…",
    bpStatusProcessing = { done, total, label -> "Processing file $done/$total: $label" },
    bpStatusNoLogs = "No Game.log and no \"logbackups\" folder found in the channel folder.",
    bpToastNoLogsTitle = "No logs found",
    bpToastNoLogsBody = "No Game.log was found in the channel folder.",
    bpStatusDone = { count, files, path -> "Done: $count blueprint(s) from $files file(s) written to $path" },
    bpToastDoneTitle = "Done",
    bpToastDoneBody = { count -> "$count blueprint(s) saved." },
    bpStatusError = { msg -> "Error: $msg" },
    bpToastErrorTitle = "Error",
    unknownError = "Unknown error",
    bpResultTitle = "Result",
    bpShowInFolder = "Show in folder",
    bpOpenJson = "Open JSON",
    cannotOpen = { name, msg -> "Could not open \"$name\": $msg" },
    bpSummaryPlayers = "Players:",
    bpSummaryNoPlayer = "(none detected)",
    bpSummaryByCategory = "Blueprints by category:",
    bpSummaryRecent = "Most recently received blueprints:",

    rfSteps = listOf("Preflight", "Images", "Extraction", "Review", "Export"),
    rfPlaceholderTitle = "Refinery extraction",
    rfPlaceholderBody = "This workflow is under construction (Phase 3 of epic #439).",
)

/** Resolve the catalogue for a language. */
fun stringsFor(lang: Lang): Strings = if (lang == Lang.DE) StringsDe else StringsEn

/**
 * Composition-local carrying the active string catalogue. Provided once at the window root by
 * `guiMain` (driven by the title-bar DE/EN toggle); every composable reads `LocalStrings.current`
 * instead of hardcoding text.
 */
val LocalStrings = staticCompositionLocalOf { StringsDe }
