# CLAUDE.md

Working notes for Claude Code (and human contributors) in this repo. Keep this file
current when the build, architecture, or the rules below change.

## What this is

**Basetool SC Extractor** (formerly *Basetool Blueprint Extractor*) — a Kotlin +
Compose-for-Desktop (Windows) app that extracts Star Citizen data locally and writes
JSON files for the basetool. Two workflows behind a Top-Tabs launcher
(`docs/DESIGN_SC_EXTRACTOR.md` in the basetool repo is the binding design):

- **Blueprints** — reads `Game.log` files and exports **which blueprints a player
  received** (the original tool, behaviour unchanged).
- **Refinery** — reads refinery work-order SETUP screenshots via a **local VLM**
  (Ollama) and emits the frozen `RefineryExtract` JSON contract (epic
  krt-iri/basetool#439, Phase 3 = #436; model/prompt/strategy decisions in
  `docs/refinery-extractor/PHASE0_FINDINGS.md`).

It ships as an MSI installer with a bundled JDK runtime (no separate Java needed) and
also runs headless as a CLI (blueprint workflow).

**Scope discipline:** exactly these two workflows. Mission data and other log analysis
stay out of scope — for blueprints, capture every detail the log carries; for refinery,
read the SETUP panel only (PROCESSING is deferred). Inference always runs locally via
the user's Ollama; never add a cloud-inference path.

## Commands

Run from the **repo root** (not a subfolder), with **JDK 25** active. On Windows use
`.\gradlew.bat`.

```powershell
.\gradlew.bat test                                              # unit tests (the source of truth for behavior)
.\gradlew.bat run                                               # launch the GUI
.\gradlew.bat run --args="<channelFolder> <outputJson>"         # headless CLI
.\package-msi.ps1                                               # build the MSI -> dist\
```

- CLI args: `<channelFolder> <outputJson>` — `channelFolder` is an SC channel dir
  (e.g. `…\StarCitizen\LIVE`); the app reads its `Game.log` + every `*.log` in the
  `logbackups\` subfolder. When the channel is **LIVE** and a sibling `HOTFIX` folder
  with logs sits next to it, that channel is swept in too (crafting knowledge is
  account-wide but each channel logs separately, so HOTFIX-farmed blueprints would
  otherwise be missed). No args ⇒ GUI.
- **Build the MSI only via `package-msi.ps1`**, never `gradlew packageMsi` directly
  (see *Packaging* below).

## Critical guardrails — do not break these

1. **`game-log/` is private and must never be published.** It holds real `Game.log`
   files with a player's handle/IDs and play data. It is gitignored; keep it that way.
   Never commit it, paste its contents, or echo log lines into anything that could be
   published. Sample/edge-case data for tests lives in `src/test/resources/sample.log`
   (safe, synthetic) — use that, not `game-log/`.
1a. **Real refinery screenshots are private too — never commit them.** Captures of the
   refinement terminal contain the player handle and the account balance. They live
   only under the gitignored `spike-phase0/work/` (or outside the repo); anything
   committed (test fixtures, README imagery) must be synthetic or fully redacted. The
   same applies to anything derived from them that quotes personal fields.
2. **The app must write nothing into its own install directory.** Clean uninstall is a
   verified feature and works *because* the program is stateless on disk (no config,
   no logs next to the exe). Exported JSON goes to the user-chosen path only. If you
   ever add state, put it under the user's data dir — never the install dir — or
   residue-free uninstall breaks.
3. **JDK 25 must drive both compile and the bundled runtime.** `kotlin.jvmToolchain(25)`
   handles compile/bytecode; `compose.desktop.application.javaHome` is *separately*
   pinned to the JDK 25 toolchain. If you drop the `javaHome` pin, jpackage builds the
   bundled runtime from the Gradle daemon's JDK (often 21) and the shipped app crashes
   at launch with `UnsupportedClassVersionError`. Keep both in lockstep.
4. **Only OFL-licensed fonts in the repo.** The brand display face (Ethnocentric) is
   commercial and was intentionally swapped for **Audiowide** (SIL OFL) for the public
   repo; `fonts/Audiowide-OFL.txt` ships the license. Do not reintroduce a
   license-restricted font into version control.
5. **Don't re-litigate the packaging decision.** Single-exe/portable approaches
   (warp-packer, IExpress, .NET bootstrapper) were explored and rejected. Ship the MSI.

## Architecture / data flow

`com.basetool.bpextractor`:

- **`BlueprintParser.kt`** — *pure, side-effect-free* per-file parsing. Streams the log
  line by line (`useLines`) so multi-hundred-MB files never load whole. Returns
  `FileResult(player, blueprints)`. No I/O orchestration, no disk writes — keep it that
  way (it's the easiest part to unit-test).
- **`BlueprintExtractor.kt`** — orchestration: `findLogFiles(channelFolder)` (when the
  folder is LIVE it also appends a sibling `HOTFIX` channel's logs via
  `siblingHotfixFolder`) → parse each → aggregate per-player counts → sort
  chronologically → assemble `BlueprintExport`. Also `writeJson`. No line-level parsing
  here.
- **`model/Models.kt`** — `@Serializable` data classes (`BlueprintEvent`,
  `PlayerSummary`, `BlueprintExport`). The exported JSON *is* this shape.
- **`Main.kt`** — entry point. No args ⇒ Compose GUI (`guiMain`); args ⇒ `runCli`. Keep
  the GUI a thin shell over `BlueprintExtractor`; business logic stays in the parser/
  extractor so tests cover it without a UI.
- **`ui/`** — `Theme.kt` (KRT brand tokens), `KrtComponents.kt` (HUD components),
  `WindowChrome.kt` (custom undecorated title bar), `Navigation.kt` (Top-Tabs bar +
  step stepper + DE/EN toggle), `StartScreen.kt` (launcher), `RefineryScreen.kt`
  (refinery workflow surface), `i18n/Strings.kt` (the DE/EN string catalogues +
  `LocalStrings`).

## Conventions

- **Kotlin official code style** (`kotlin.code.style=official`). Match the surrounding
  style; small, pure functions; data classes for models.
- **Comments in English; user-facing UI strings via the i18n catalogue.** Every
  UI string lives in `ui/i18n/Strings.kt` (German default + full English parity,
  switched by the title-bar DE/EN toggle — design spec §6). Never hardcode UI text at a
  call site; add a property to BOTH catalogues. CLI output stays English (scripting
  surface). README is German; this file is English by convention (agent/dev guidance).
- **Model fields are nullable when the log may omit them** (`player`, `notificationId`,
  `queueSize`, `gameBuild`). `productName`/`receivedAt` are always present. JSON uses
  `encodeDefaults = true` + `prettyPrint`; `schemaVersion` is explicit — bump it if you
  change the export shape.
- **`geid`/`accountId` are intentionally NOT stored or exported.** The parser reads the
  char-status line for the *handle* only; do not add the numeric IDs back to the model.

## Parsing domain notes (hard-won — preserve in tests)

- The one authoritative line is `Added notification "Received Blueprint: <name>: " [<id>]`.
  Each blueprint appears ~6× in a log (the add, a queue echo, and `UpdateNotificationItem`
  Next/StartFade/Remove). **Anchoring on `Added notification` is what prevents ~6× over-
  counting** — don't loosen that regex.
- Item names contain quotes (`Yubarev "Mirage" Pistol`), parens (`… (10 cap)`), slashes
  (`Sth/2/C Cirrus`), hyphens, and trailing spaces (trimmed). The name terminates at
  `: " [<digits>]`. Any regex change must keep the existing edge-case tests green.
- `MissionId` on a blueprint line is always all-zero ⇒ useless. The **player** comes from
  login lines (`User Login Success - Handle[…]`, the char-status line, or `nickname="…"`),
  first match wins. The **build number** comes from the file name `Game Build(<n>)`.
- Characterization check: the real (private) `game-log/` dump yields exactly **179
  blueprints** for player **`greluc`**. If a parser change moves that number, understand
  why before accepting it.

## UI / design

- Follows the **`das-kartell-design` Claude skill** (local, gitignored under `.claude/`).
  The tokens are mirrored in `ui/Theme.kt` so contributors without the skill can still
  match the palette: house orange `#E77E23` on black, Audiowide (display, UPPERCASE) +
  Lato (body), square corners, diagonal HUD corner-brackets, orange "bloom" instead of
  soft shadows.
- **Exactly one filled orange CTA per context** (here: "Blueprints extrahieren").
  Secondary actions are ghost buttons; labels are neutral gray. Reuse the components in
  `KrtComponents.kt` rather than restyling Material defaults ad hoc.
- The window is **undecorated** with a custom title bar (`WindowChrome.kt`). Pitfall:
  `androidx.compose.ui.window.WindowDraggableArea` does **not** resolve in Compose
  Multiplatform 1.11 — dragging is implemented manually via AWT `window` + `MouseInfo`.
  Don't "fix" it back to `WindowDraggableArea`.
- Verify GUI changes by actually launching the app (Skiko/Compose init on the slim
  runtime is the thing that breaks), not just by passing tests.
- **Star Citizen Fankit compliance:** the GUI footer (`CommunityDisclaimerFooter`) and the
  README show the official *Made by the Community* logo
  (`src/main/resources/MadeByTheCommunity_Black.png` = white-ink, **unaltered**: no
  recolor/flip/distort/effects, full opacity, proportional scale only) plus the required
  trademark notice (`Legal.TRADEMARK_NOTICE`, verbatim from the Fankit Guidelines, ≥10pt,
  always visible). Keep both wherever the SC brand is shown; don't change the notice
  wording (Squadron 42 is intentionally absent — it's not in the required notice).

## Build & packaging gotchas

- **MSI / WiX:** built with **WiX 7** (pinned via `$wixRequiredMajor` in
  `package-msi.ps1`) — jpackage supports WiX 4+ since JDK 24 (JDK-8319457). The
  notorious "error 144 with WiX 4+" (JDK-8356592) was NOT a jpackage bug: jpackage
  passes `-ext WixToolset.Util.wixext`/`.UI.` *unversioned* and `wix.exe` resolves them
  to the *highest* version in the extension cache — with mixed WiX majors installed
  (e.g. v6 + v7), the older `wix.exe` picks the newer major's extensions, can't load
  them, and exits 144 (WIX0144); that's also why the pin matters. WiX **v7+** requires
  a one-time per-user OSMF EULA acceptance (`wix eula accept wix7`; the fee only
  applies above ~$10k annual revenue — see issue #1). `package-msi.ps1` handles all of
  it: picks the newest installed WiX 7.x (process-scoped PATH only), preflights EULA +
  extensions with readable errors (auto-accepts the EULA **only on CI**), and
  bootstraps a local dotnet-tool WiX under `tools\wix` on bare machines. It changes
  nothing on the system — keep building the MSI via the script, not
  `gradlew packageMsi`.
- **Slim runtime:** the bundle is *not* all-modules. `modules("java.instrument",
  "jdk.unsupported")` plus the plugin's auto-detected base set. If you add a dependency
  that needs another JDK module, re-run `gradlew suggestRuntimeModules`, add it, rebuild,
  **and re-launch the GUI** to confirm the slim runtime still boots.
- `--enable-native-access=ALL-UNNAMED` (in `jvmArgs`) silences JDK 25's native-access
  warnings from Skiko's `System.load()` — keep it.
- Gradle **configuration cache is off on purpose** (Compose jpackage tasks aren't
  cc-safe). Don't enable it. The `compose.material3` deprecation warning is benign and
  intentional (the explicit Material3 coordinate is only alpha).

## When you change…

- **the JDK version** → update `jvmToolchain` *and* confirm the `javaHome` toolchain
  resolves to the same JDK; rebuild the MSI and launch the GUI from the app image.
- **the parser/regex or model** → update `sample.log` + the tests; re-confirm the
  179/`greluc` characterization against the local `game-log/`.
- **bundled modules or the runtime** → `suggestRuntimeModules`, rebuild, GUI-launch test.
- **the export shape** → bump `schemaVersion`.
- **the released version** → don't edit it anywhere by hand; it comes from the git tag
  (see *Releases*). CI sets `project.version`, the `generateBuildInfo` task writes it into
  the generated `BuildInfo.VERSION`, and `BlueprintExtractor.TOOL_VERSION` (CLI banner +
  export `toolVersion`) reads that — so the MSI and the app's reported version stay in
  lockstep. The dev fallback in `build.gradle.kts` stays `1.0.0`.

## Releases (CI)

GitHub Actions — [`.github/workflows/ci.yml`](.github/workflows/ci.yml), on
`windows-latest`:

- **Push to `main` / PRs / manual dispatch:** runs `test` + `createDistributable` (a
  packaging smoke test — no MSI/WiX).
- **Push a `v*` tag (e.g. `v1.2.0`):** after checks pass, builds the MSI via
  `package-msi.ps1` and publishes a GitHub Release with the `.msi` attached. Suffixed
  tags (`v1.2.0-rc1`) publish as pre-releases.

**The release version is the tag, not a constant in the build.** The workflow strips the
leading `v` and exports `APP_VERSION`, which `build.gradle.kts` reads (`System.getenv` →
`-PappVersion` → `1.0.0` dev fallback); the MSI `packageVersion` drops any `-suffix` to
stay numeric `major.minor.build`. To cut a release:

```powershell
git tag v1.2.0 ; git push origin v1.2.0
```

CI builds the MSI through `package-msi.ps1`, so the WiX setup (pinned WiX 7 — installed
or bootstrapped as a local dotnet tool, OSMF-EULA auto-acceptance on CI, Util/UI
extensions) is honored on the runner too. The release job is the only one granted
`contents: write`.

## Repo / publishing

- Public repo: `https://github.com/krt-iri/basetool-bp-extractor` (branch `main`).
- **License: GPL-3.0-or-later** (`LICENSE`). Deps are permissive (Apache-2.0/BSD); the
  bundled JRE is GPLv2 + Classpath Exception (redistribution OK, does not infect app
  code). Bundled fonts (Audiowide, Lato) ship their OFL texts under
  `src/main/resources/fonts/` — if you add/replace a bundled font, add its license too.
- Gitignored and kept out of the published repo: `game-log/` (private logs), `.claude/`
  (the design skill), `_extracted/` (reference material), `tools/`, `build/`, `dist/`,
  `_gui_*`. Verify `game-log/` is absent from any commit before pushing.
- Commit/push only when the user asks.
