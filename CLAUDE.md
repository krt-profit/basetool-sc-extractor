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
  krt-profit/basetool#439, Phase 3 = #436; model/prompt/strategy decisions in
  `docs/refinery-extractor/PHASE0_FINDINGS.md`).

It ships as an MSI installer with a bundled JDK runtime (no separate Java needed) and
is operated entirely through the GUI.

**Scope discipline:** exactly these two workflows. Mission data and other log analysis
stay out of scope — for blueprints, capture every detail the log carries; for refinery,
read the SETUP panel only (PROCESSING is deferred). Inference always runs locally via
the user's Ollama; never add a cloud-inference path.

## The knowledge base (HARD RULE — read before every task)

The **Basetool Knowledge Base** is the single source of truth about the Profit Basetool as a whole:
an Obsidian vault and git repository (`basetool-knowledge`), sitting beside this repository in the
workspace. It covers every part of the system — backend, frontend, ingest, keycloak-spi, the Android
app, **this extractor**, the P4K reader — plus the roles, permissions, decisions, incidents and
runbooks around them.

**This rule is binding on every AI agent working on the Basetool or any of its parts and
repositories, without exception.**

- **Read it before you start any task**, not after. Enter through its root map
  (`00 Maps/Basetool.md`); its own `CLAUDE.md` explains how it is written. For this repo start with
  `SC Extractor`, `Ingest`, `Keycloak`, `Raffinerie` and `Blueprints` — the ingest contract, the
  device grant, the DPoP binding and the two allowlists are all documented there, and the server
  half of each is in the `basetool` repo where you cannot see it from here.
- **Every change here updates the knowledge base in the same unit of work**: a workflow change, a
  new field in the export contract, an auth or DPoP behaviour, a packaging decision, a guardrail
  learned the hard way. It is not written afterwards and never "caught up later".
- The vault is a **separate git repository**, so nothing in this repo's CI can gate it. That is
  exactly why it is a hard rule: no build will fail if you skip it, and skipping it is still an
  incomplete change.
- **It must never drift from reality.** It represents the truth about this project and every part of
  the system orients by it — a drifted vault is worse than none, because each stale note still reads
  as authoritative. **If you notice it is out of date, incomplete, or does not cover something,
  update or extend it immediately as part of the work in hand**, even when the gap lies outside your
  task. When the vault and the code disagree the **code** is right, and the note gets corrected in
  the same session, saying so and dated.
- Move `updated:` on every note you re-checked, and run `python "90 Meta/vaultcheck.py"` from the
  vault root before committing.
- **If you cannot find the vault, ask the user where it is at the start of the session.** Its
  location is workspace-specific and it is **not** a submodule, so a fresh machine, a worktree or a
  CI runner may simply not have it beside this repo. Do not guess a path, do not proceed as if the
  rule did not apply, and do not silently skip it — a missing vault is a question to ask, never a
  rule to drop.

> **Never a secret and never personal data in the vault** — and here that has a specific edge:
> nothing derived from `game-log/` or from a real refinery screenshot may go in, since both carry a
> player handle and a screenshot carries the account balance (guardrails 1 and 1a below). Write the
> *shape* of a fact, never a captured value.

## Commands

Run from the **repo root** (not a subfolder), with **JDK 25** active. On Windows use
`.\gradlew.bat`.

```powershell
.\gradlew.bat test                                              # unit tests (the source of truth for behavior)
.\gradlew.bat run                                               # launch the GUI
.\package-msi.ps1                                               # build the MSI -> dist\
```

- **Build the MSI only via `package-msi.ps1`**, never `gradlew packageMsi` directly
  (see *Packaging* below).

**Refinery changes are verified against the private sample corpus, not by unit tests alone.**
`PromptSmokeTest` runs the whole live path (Locate → Normalize → read → stitch → validate) over a
folder of order folders and diffs the result against a golden-expected JSON; it is trivially green
when the env vars are unset, and it needs a running Ollama. `PanelDumpTest` writes just the
normalized crops — that is the fastest way to see a Locate regression, because a wrong crop still
produces a plausible-looking export.

```powershell
$env:PROMPT_SMOKE_DIR      = "<the sample corpus>"          # one folder per order
$env:PROMPT_SMOKE_EXPECTED = "<corpus>\golden-expected.json" # PRIVATE, lives next to the corpus
.\gradlew.bat test --tests '*PromptSmokeTest*' --rerun-tasks   # env changes don't invalidate the task
```

An **onnxruntime bump** is checked differently, and much more cheaply: `OcrDigestTest` runs the
bundled classical-OCR reader over the whole corpus and prints a SHA-256 of every cell it
recognised, with its box. ONNX Runtime's CPU inference is deterministic, so a clean bump is
**bit-for-bit identical** — run it before and after and diff. No Ollama, under a minute, and any
difference at all is real (1.22.0→1.27.0 and 1.29.0→1.30.0 both came back byte-identical).

```powershell
$env:OCR_DIGEST_DIR = "<the sample corpus>"
$env:OCR_DIGEST_OUT = "<outside the repo>\ocr-digest-before.txt"   # then ...-after.txt
.\gradlew.bat test --tests '*OcrDigestTest*' --rerun-tasks
```

An **OCR-model swap** is the opposite case: the digest is guaranteed to change and says nothing
about whether the pipeline got better. Put each candidate in a folder (`det.onnx`, `rec.onnx`,
optional `dict.txt` and `det.properties`) and add `PROMPT_SMOKE_OCR_CANDIDATES=<folder of those>`
to the `PromptSmokeTest` run: `OcrCandidateEval` validates every candidate against the *same* VLM
reads and reports what the cross-reader changed, flagged and mis-read. Run it with and without
`PROMPT_SMOKE_VERIFY_MODEL` — without it the lower tiers' 2-vote fusion turns every OCR
disagreement into a review flag. The criterion is cells rescued or flagged on the corpus, settled
against the pixels, never a model card (issue #55; the round that moved to PP-OCRv6 small is in
`docs/refinery-extractor/PHASE0_FINDINGS.md`).

Add `PROMPT_SMOKE_VERIFY_MODEL=qwen3-vl:4b-instruct` for the config the expected file was generated
with. **Never** regenerate the whole file with `PROMPT_SMOKE_WRITE_EXPECTED=1` to make a diff go
away — merge the orders you meant to add and leave the rest, and settle any disputed cell against
the pixels of the capture, not against whichever run you like better. Corpus and expected file are
private (guardrail 1a) and live outside the repo; ask for their path.

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
   verified feature and works *because* nothing lands next to the exe (no config, no
   logs). Exported JSON goes to the user-chosen path only. What little state the app does
   keep lives under the user's data dir — `config/AppConfig.kt` writes
   `%APPDATA%\Basetool SC Extractor\config.json` (ingest URL, send consent, the last
   channel folder) — **never** the install dir, or residue-free uninstall breaks. Two
   holders write that file (the blueprint step and the send flow), so every write is
   load → `copy(…)` → save; skip the reload and one silently drops the other's field.
   Pasted/dropped refinery images without a picked folder follow this rule via a session
   temp dir (`ImageIntake.tempFolder()`) that a shutdown hook removes — reuse that pattern
   for anything similar.
3. **JDK 25 must drive both compile and the bundled runtime.** `kotlin.jvmToolchain(25)`
   handles compile/bytecode; `compose.desktop.application.javaHome` is *separately*
   pinned to the JDK 25 toolchain. If you drop the `javaHome` pin, jpackage builds the
   bundled runtime from the Gradle daemon's JDK (often 21) and the shipped app crashes
   at launch with `UnsupportedClassVersionError`. Keep both in lockstep.
4. **Only OFL-licensed fonts in the repo.** The brand display face (Ethnocentric) is
   commercial and ships nowhere; since the Lato-only redesign the GUI uses **Lato**
   (SIL OFL, `fonts/Lato-OFL.txt`) exclusively — headlines are Lato Bold UPPERCASE,
   there is no separate display face. Do not reintroduce a license-restricted font
   (or any second font family) into version control.
5. **Don't re-litigate the packaging decision.** Single-exe/portable approaches
   (warp-packer, IExpress, .NET bootstrapper) were explored and rejected. Ship the MSI.
6. **The basetool ingest interface is for clients @greluc has approved — only.** The
   gateway matches the token's `azp`, its scope and the payload's `tool` field against
   server-side allowlists and answers `403 CLIENT_NOT_ALLOWED` to anything else
   (`REQ-INGEST-011`; spec `docs/specs/desktop-ingest.md` in the basetool repo). That
   gates the *software*, not the people — every member may upload, with the approved
   extractor. Practically: `DeviceGrantClient.CLIENT_ID` and `RefineryPipeline.TOOL`
   (both `"basetool-sc-extractor"`) are **contractual constants**. Never rename them as
   a drive-by; it is a two-sided rotation (add the new value to the server allowlist,
   ship, drop the old one once its `client_id` metric is quiet). Don't add a second
   ingest client, and don't work around a 403 — ask for an allowlist entry instead.

## Architecture / data flow

`com.basetool.bpextractor`:

- **`BlueprintParser.kt`** — *pure, side-effect-free* per-file parsing. Streams the log
  line by line (`useLines`) so multi-hundred-MB files never load whole. Cheap literal
  substring guards run before every regex (the hot path on huge logs) — keep them as
  literal prefixes of their regexes. Returns `FileResult(player, blueprints)`; an
  optional `onBytesRead` callback feeds within-file progress. No I/O orchestration, no
  disk writes — keep it that way (it's the easiest part to unit-test).
- **`BlueprintExtractor.kt`** — orchestration: `findLogFiles(channelFolder)` (when the
  folder is LIVE it also appends a sibling `HOTFIX` channel's logs via
  `siblingHotfixFolder`; a folder with neither `Game.log` nor `logbackups/` but loose
  `*.log` files in it is read as an **archive**, non-recursively, via `looseLogsIn`)
  → parse each → aggregate per-player counts → sort
  chronologically → assemble `BlueprintExport`. `extract` returns `ExtractionResult`
  (export + `skippedFiles`): an unreadable log is skipped and reported, never fatal,
  and events whose identity (player/name/timestamp/notification id) was already seen
  in another file are counted once (guards against manually copied logs).
  `writeJson`/`toJson` serialize the export to disk/string. No line-level parsing here.
- **`ScLocalization.kt`** — reads the game's own localisation so the blueprint label is never
  guessed: `g_language` from `<channel>\user.cfg` and the
  `crafting_hud_notification_received_blueprint` value from every
  `<channel>\data\Localization\*\global.ini`. Streams the ~11 MB files behind a literal guard and
  stops at the key; handles the UTF-8 BOM and both `key=` and `key,FLAG=` shapes. Read-only and
  best-effort — a missing folder, unreadable file or absent key yields an empty result, never an
  exception. The pure line parsers are separate and unit-tested without a disk.
- **`model/Models.kt`** — `@Serializable` data classes (`BlueprintEvent`,
  `PlayerSummary`, `BlueprintExport`). The exported JSON *is* this shape.
- **`update/UpdateChecker.kt`** — the GUI's startup update check against this repo's
  GitHub releases (`releases/latest`). Pure/testable parts:
  version compare, release-JSON parsing, MSI-asset selection, installer-command
  construction. Thin I/O: silent fetch (any failure ⇒ no offer), download into a **fresh,
  randomly named** folder under `%TEMP%` (`newUpdateDir()`, `Files.createTempDirectory` with
  the `basetool-sc-extractor-update-` prefix) — never the install dir (guardrail 2) and
  deliberately NOT the `ImageIntake` session temp dir, whose shutdown hook would delete the MSI
  out from under the installer — with size + SHA-256 verification, then a detached hidden
  PowerShell helper (`INSTALLER_SCRIPT`) runs `msiexec /i` after the app exits and
  deletes the MSI, itself and the folder again; `cleanupLeftovers()` sweeps every folder with
  that prefix on every GUI start as the crash fallback. Only release metadata is fetched;
  nothing is uploaded.
  - **The updater fails closed (SIB-SEC-05, 2026-09-22).** No offer unless the MSI asset's URL
    starts with `RELEASE_DOWNLOAD_PREFIX` (this repo's `releases/download/`, parsed — no
    user-info, port or `..`) **and** the API answer carries a well-formed `sha256:` `digest`.
    The helper gets that digest as its second positional argument and re-hashes the MSI
    (.NET `SHA256`, deliberately not `Get-FileHash`, whose module a Windows PowerShell with an
    inherited PowerShell 7 `PSModulePath` failed to load on the CI runner) **immediately before
    every `msiexec`**, the elevated retry included; a
    mismatch skips the install and the retry prompt. `UpdateCheckerTest` runs the helper's
    own functions in real Windows PowerShell with `Start-Process` stubbed, so that property
    is tested, not just grepped. Never loosen either check to make an odd release work —
    fix the release.
- **`net/`** — the only outbound path besides the update check. `BasetoolIngestClient`
  POSTs an export to the gateway and surfaces the RFC 7807 `detail` (+ `fieldErrors`);
  `auth/DeviceGrantClient` runs the RFC 8628 device grant against the **prod** Keycloak
  (hardcoded issuer; only the ingest base URL is config), `auth/CredentialStore` is the
  DPAPI-backed vault for the one "remember me" `StoredCredential`. Both clients accept a
  server URL only through `net/TransportPolicy` — **parsed** with `java.net.URI`: `https`, or
  plain `http` to exactly `localhost` / `127.0.0.1`, and never with user-info. A prefix check
  (`startsWith("http://localhost")`) let `http://localhost.attacker.tld` and
  `http://127.0.0.1@attacker.tld/` through (SIB-SEC-09); don't reintroduce one.
  - **Identity lives on the app origin, and `PROD_ISSUER` is the only copy of that fact here.**
    ADR-0166 moved Keycloak to `https://profit-base.online/auth/realms/iri` and retired
    `keycloak.profit-base.online` outright; the constant still named the dead host and every send
    failed with `token refresh failed: (unrecognized_name)` — a *fatal TLS alert*, because a
    wildcard DNS record still resolves the name while the edge serves no vhost for it. Nothing on
    either side can check this literal (ADR-0167's single-source gate stops inside the `basetool`
    repo), so **an identity host move means cutting a release here, in the same change.**
    The fix shipped as **v2.9.1** (2026-09-14), which is therefore the oldest build that can
    still send; older ones only read and save JSON.
  **DPoP (RFC 9449, `REQ-INGEST-012`)** binds those tokens to a client-held EC P-256 key
  (`auth/Dpop.kt`), and the key that outlives the process is a **non-exportable Windows CNG
  key** (`auth/CngDpopKeyStore`, FFM against `ncrypt.dll`): the TPM (Microsoft Platform Crypto
  Provider) when there is one, else the Software Key Storage Provider with export policy 0.
  Proofs are signed with `NCryptSignHash`; the private half never enters the JVM. The
  Credential Manager record holds **only the refresh token and the key's name**
  (`StoredCredential(refreshToken, dpopKeyName)`), so a copied record is useless on any other
  machine.
  - **Corrected 2026-09-22 (SIB-SEC-04).** This file used to say "the refresh token sitting on
    disk is worthless if copied" while `CredentialStore` stored the refresh token **and** the
    exported PKCS#8 key in the *same* blob — so a copied record was the whole login, and DPoP
    only protected against the token leaking *alone*. That record shape is now read only as
    `CredentialRecord.LegacyExportedKey`: on the next send (or disconnect) its token is revoked
    with a proof from the old key, the record is deleted, and the member signs in once more
    (the overlay says why: `SendState.Authenticating.keyUpgrade`). What remains true of the
    software fallback: code already running as this user on this machine can still *ask*
    Windows to sign; only the TPM path resists an administrator.
  - **Keys never leak.** Every key a login does not end up naming is deleted again (failed
    grant, dead token, key replaced, disconnect). Without a usable key storage the send falls
    back to an in-memory session key, and a token bound to it is **not** remembered.
    `NCryptDeleteKey` takes no `NCRYPT_SILENT_FLAG` — the TPM provider refuses it there and
    the key silently stayed behind until `CngDpopKeyStoreTest` caught it.
  - The proof is **always offered** at the token endpoint but the `DPoP` scheme is used at the
    gateway **only when the answer's `token_type` says the server actually bound the
    token**, which is what keeps a released build working against a Keycloak or gateway
    that has DPoP off (presenting an *unbound* token under the DPoP scheme is a hard 401).
    Proof construction is pure JDK code — no JOSE dependency, no extra jlink module (FFM is
    `java.base` too). Never log a key, a proof or a token.
  - **Clock drift is a real failure mode** — Keycloak accepts `iat` only in −25s…+15s and
    checks the proof *before* the grant, so a desktop clock ~15s fast breaks login
    outright, where the timestamp-free bearer builds were immune. `ServerClock` measures
    the offset from each server's `Date` header and corrects `iat`; a rejected proof is
    retried **exactly once** from the corrected clock (that is the *only* retry, and it
    cannot repeat). If it still fails, the measured offset reaches the UI so the user is
    told to sync their clock — but only then, never for a drift we already fixed.
  - **The RFC 9449 §8 nonce handshake is deliberately NOT implemented.** Neither server
    issues a challenge (Spring Security 7.1 has no resource-server nonce support at all;
    `use_dpop_nonce` appears nowhere in Keycloak), so an implementation would be code
    nobody has watched run. A challenge is instead *detected*, reported on stderr and
    named in the UI (`DpopNonce.CODE`) — a loud, once-only event that needs a release.
    Don't "complete" it speculatively; do implement it if a challenge ever shows up.
- **`Main.kt`** — entry point: opens the Compose GUI (`guiMain`). Keep
  the GUI a thin shell over `BlueprintExtractor`; business logic stays in the parser/
  extractor so tests cover it without a UI. `guiMain` also owns the update flow state
  (check on start, download with progress, launch installer, exit).
- **`ui/`** — `Theme.kt` (KRT brand tokens), `KrtComponents.kt` (HUD components),
  `WindowChrome.kt` (custom undecorated title bar), `Navigation.kt` (`CommandStrip`:
  Top-Tabs + the active workflow's inline stepper in ONE band, plus the DE/EN toggle),
  `StepScaffold.kt` (compact `SectionHead` · growing scrollable body · pinned footer —
  every workflow screen sits on it; the primary CTA always lives in the footer),
  `StartScreen.kt` (launcher — the only screen with the big `GreetingHeader`),
  `UpdateBanner.kt` (the start screen's update offer: `UpdateUiState`
  Hidden/Available/Downloading/Installing/Failed; install is that screen's one filled
  CTA, "Später" hides it for the session — no persisted skip; `config.json` keeps only the
  three fields named in guardrail 2),
  `RefineryScreen.kt` (refinery workflow surface), `refinery/` (the five step screens +
  `RefineryUiState` — per-image checkboxes decide which images get extracted; while the
  images step is on screen the picked folder is polled once per second
  (`rescanFolder`): a pure add/remove diff, so checkbox choices and ✕-removed tiles
  survive every tick;
  grid tiles are decoded from the image **header** (native size → `Locate.isPrecropped`) plus a
  source-subsampled thumbnail, up to 4 at once on `Dispatchers.IO` — a 4K capture is never
  decoded at full size just to become a 240 px tile; only the extraction run does that;
  `ImageIntake.kt` is the pure intake logic for clipboard pastes — the window-level
  Strg+V handler lives in `Main.kt` — and external drag & drop: images persist into
  the picked folder or, without one, into the session temp dir from guardrail 2),
  `FilePicker.kt` (the KRT in-app file/folder picker — never native dialogs),
  `i18n/Strings.kt` (the DE/EN string catalogues + `LocalStrings`).

## Conventions

- **Kotlin official code style** (`kotlin.code.style=official`). Match the surrounding
  style; small, pure functions; data classes for models.
- **KDoc in English (no other comments — see below); user-facing UI strings via the i18n catalogue.** Every
  UI string lives in `ui/i18n/Strings.kt` (German default + full English parity,
  switched by the title-bar DE/EN toggle — design spec §6). Never hardcode UI text at a
  call site; add a property to BOTH catalogues.
- **Everything on GitHub is English** — issues, pull requests, commit messages, releases
  (including the notes the VirusTotal step renders), this file and the README. The GUI is
  the only German-facing surface, and it goes through the i18n catalogue. The README was
  German until 2026-08-19; don't reintroduce German prose outside `Strings.kt`.
  - **`Strings` is an `interface`, `StringsDe`/`StringsEn` are `object`s — keep it that
    way.** A flat `class Strings(val …: String, …)` hits the JVM's 254-value-parameter
    constructor limit and throws `ClassFormatError` at class-LOAD time (the GUI crashes on
    launch; `compileKotlin` and the unit tests do NOT catch it). The interface has no
    constructor, so the limit can't recur. To add a string: add `val name: Type` to the
    interface and `override val name = …` to BOTH objects (function-typed entries need the
    explicit type on the override, e.g. `override val foo: (Int) -> String = { n -> … }`).
    Verify by launching the GUI, not just by tests.
- **Model fields are nullable when the log may omit them** (`player`, `notificationId`,
  `queueSize`, `gameBuild`). `productName`/`receivedAt` are always present. JSON uses
  `encodeDefaults = true` + `prettyPrint`; `schemaVersion` is explicit — bump it if you
  change the export shape.
- **`geid`/`accountId` are intentionally NOT stored or exported.** The parser reads the
  char-status line for the *handle* only; do not add the numeric IDs back to the model.

## Code comments (HARD RULE — no comments besides KDoc)

**The code carries no comments besides proper KDoc, the KDoc is short and precise, and no history is
kept in either.** Binding on every change and every agent, main and test sources alike — the same
rule as the main repository's ADR-0214 (`basetool/docs/adr/0214-code-carries-no-comments-besides-javadoc.md`).

- **No comments.** No `//` or `/* */` in Kotlin, no `<!-- -->` in XML resources, no `#` in YAML,
  properties, ProGuard rules or scripts, no commented-out code or config.
- **What stays:** KDoc `/** */` on the declarations it documents (Python docstrings and PowerShell
  comment-based help under the same rules), licence headers, and tool directives with no prose
  after them (`# shellcheck disable=`, `# noqa`, …). `@Suppress` and other annotations are not
  comments.
- **KDoc is short, precise and carries no history.** One summary sentence, a contract sentence only
  when a caller needs it, then the tags. No dates, PR or issue numbers, "previously" / "now" /
  "used to", migration or incident stories, rationale essays or pointers to other comments; a bare
  `REQ-…` / `ADR-…` pointer is fine.
- **The reasoning goes into the commit message and the PR body.** A durable fact a later change
  needs goes into the spec, the ADR, the docs or the Basetool knowledge base — never into a comment.
- **Out of scope:** generated and vendored files, Markdown, and test fixtures whose comments are the
  data under test.
- **Mind live syntax.** Before deleting a comment, check it held nothing a tool reads.

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
  first match wins. The **build number** comes from the file name `Game Build(<n>)`, and —
  for the live `Game.log`, whose name carries none — from the `BackupNameAttachment="…
  Build(<n>) …"` header on line 1 (present in all 424 corpus files, always agreeing with the
  file name). File name wins; the header is read once, on the first line only.
- **The label is localised, the line around it is not.** The text comes from the game's
  localisation tables under the invariant key `crafting_hud_notification_received_blueprint`,
  whose value is a *format*: `Received Blueprint: %s` / `Bauplan erhalten: %s`. The wrapper —
  `Added notification "<rendered>: " [<id>] to queue. New queue size:` — is a C++ format literal
  and stays English in every language. Since `%s` is the **last** thing the localised value
  contributes, everything after the item name is engine-side and *cannot* vary by language;
  that is why the `: " [<id>]` terminator is safe to anchor on. Getting the label wrong is a
  *silent* failure: a non-English client yields zero blueprints and the GUI reports a
  successful export.
- **Don't ship guesses — read the install.** `ScLocalization.detect(channelFolder)` reads
  `user.cfg` (`g_language`) and every `data/Localization/*/global.ini` in the picked folder, so
  the app matches the string the game will actually write. All installed languages are read, not
  just the active one: a scan spans months and the player may have switched. A rewording by CIG
  or by the translators then needs no release here. `BlueprintParser.BUILT_IN_FORMATS` is only
  the fallback — vanilla English (its `global.ini` lives inside `Data.p4k`) and archive folders
  with no game next to them. It stays a **closed whitelist**: entries go in on authoritative
  evidence only — the localisation source (`rjcncpt/StarCitizen-Deutsch-INI`, what the *SC
  Deutsch Launcher* installs; note it ships a Swiss variant, `Bauplan überchoo`) or a real log.
  Verified there: exactly one key produces each of those values, so none can collide with
  another notification kind.
- Formats are split at `%s`, not treated as a prefix, so a translation that puts the name first
  would work too. Matching the localised part is case-insensitive; the `Added notification "`
  prefilter is **not**, deliberately, because it runs on every one of ~7.9M lines and guards a
  string that cannot vary.
- **German item names differ from English ones**, so a German player's `productName` will not
  always match the basetool catalogue: of our 177 corpus names, 127 resolve in the English
  `global.ini` and 13 of those are written differently in German — all of them the
  `(30 cap)` → `(30 Schuss)` suffix. Not fixable here (`productName` must stay byte-verbatim,
  it *is* the matching key); tracked on the basetool side.
- Do NOT anchor on the bare skeleton without the label whitelist: `Added notification "` alone
  matches ~19,000 non-blueprint notifications in the corpus (~105 false positives per real
  blueprint). The ~6× overcounting guard is owned by `Added notification`, the false-positive
  guard by the whitelist — both are needed.
- Class/Size/Grade rides along **inside the name as a prefix** (`Sth/2/C Cirrus`; 23 of 177
  distinct names, classes Ind/Mil/Sth). The parenthesised *suffix* form (`… (Civ/3/A)`) that
  other tools strip does not occur once in 1.82 GB of English logs — don't port a suffix regex.
  `productName` stays byte-verbatim either way: it is the basetool's matching key.
- The account-wide blueprint library is **not** in the log — the game fetches it from a backend
  service and only a channel-reuse line reaches the file. The export therefore means "received
  while a log existed", never "owned". Negative result, already searched; don't redo it.
- PTU/EPTU/TECH-PREVIEW were measured (5 / 0 / 1 blueprint events against HOTFIX's 43): the
  `LIVE` + sibling `HOTFIX` sweep stays as is. Pointing the picker at a test channel already
  works, so those stay opt-in.
- Characterization check: the real (private) `game-log/` dump yields exactly **179
  blueprints** for player **`greluc`**. If a parser change moves that number, understand
  why before accepting it. Note the dump is a *flat* archive folder (no `Game.log`, no
  `logbackups/`) — it is readable because `collectChannelLogs` falls back to loose `*.log`
  files; before that fallback existed the folder scanned to zero.

## UI / design

- Follows the **`das-kartell-design` Claude skill** (local, gitignored under `.claude/`).
  The tokens are mirrored in `ui/Theme.kt` so contributors without the skill can still
  match the palette: house orange `#E77E23` on black, **Lato-only typography**
  (headlines Lato Bold UPPERCASE with 0.05em tracking, body Lato Light — no
  Audiowide/Ethnocentric), square corners, diagonal HUD corner-brackets, orange
  "bloom" instead of soft shadows.
- Default window is **1180×820 dp**, resizable down to **640×520** (the `ResizeCorner`
  enforces the floor). The small-window guarantee comes from `StepScaffold`: the body
  scrolls, the footer CTA stays pinned — never put a primary CTA inside a scroll body.
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
  README show the official *Made by the Community* logo (GUI footer:
  `src/main/composeResources/drawable/made_by_the_community_black.png`, loaded via the Compose
  resources `Res`; README: the `docs/img/` copies — white-ink, **unaltered**: no
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
- **The WiX bootstrap is checksum-pinned** (`$wixBootstrapSha512`): `wix.7.0.0.nupkg`
  must match the nuget.org catalog's `packageHash` (SHA-512, base64) before `wix.exe`
  runs, and a download that mismatches is refused. `tools\wix` is **never reused** — it is
  deleted and installed afresh on every run, because the pin covers the `.nupkg` but not
  the files unpacked beside it nor the `wix.exe` shim dotnet generates. The runner image
  has no WiX 7, so **every release build** takes this path — the pin is what verifies the
  CI download. Bump version **and** hash together, and never pin the `.nupkg.sha512` file
  NuGet writes beside the package (that is NuGet's content hash, not the file's). An
  installed WiX 7 on `PATH` wins over `tools\wix` and is trusted as installed.
- **The Util/UI extensions are pinned too** (`$wixExtensionSha512`, the SHA-512 of each
  `wixext7\<id>.dll` inside its catalog-verified nuget.org package). For the pinned WiX
  version, every copy in the user (`~\.wix`) and machine extension cache must match; a bad
  user-cache copy is replaced once, a bad machine-cache copy is reported, a newer cached 7.x
  (which `wix.exe` would load instead) is refused, and the script downloads no extension it
  has no pin for. Moving WiX means moving all three hashes.
- **The Compose plugin's own WiX 3.11 is switched off**:
  `compose.desktop.application.downloadWix=false` in `gradle.properties`. With it off the
  plugin still prepends `wixToolsetDir` to every jpackage task's `PATH` and fails on the
  unset value, so `build.gradle.kts` hands it an empty `build\no-bundled-wix`. `packageMsi`
  refuses to start unless the first `wix.exe` on `PATH` is WiX 4+, and names the script.
  Don't remove either half: the zip was fetched from GitHub with no checksum.
- **Slim runtime:** the bundle is *not* all-modules. `modules("java.instrument",
  "jdk.unsupported", "java.net.http", "jdk.management")` plus the plugin's auto-detected base
  set. If you add a dependency that needs another JDK module, re-run
  `gradlew suggestRuntimeModules`, add it, rebuild, **and re-launch the GUI** to confirm the
  slim runtime still boots. (The bundled runtime ships no standalone `java.exe` — jpackage
  strips launchers — so to test a dep on the slim module set non-interactively, run the full
  JDK with `--limit-modules <the runtime/release MODULES list>`.)
- `--enable-native-access=ALL-UNNAMED` (in `jvmArgs`) silences JDK 25's native-access
  warnings from `System.load()` — used by **both** Skiko's renderer **and** ONNX Runtime
  (the refinery OCR cross-reader). Keep it.
- **Bundled OCR models + ONNX Runtime:** the refinery extractor runs a local classical-OCR
  cross-check (`TextDetector`/`DigitOcr`/`PanelOcr`, PP-OCRv6 small via the
  `com.microsoft.onnxruntime:onnxruntime` dep) as a decorrelated third reader for the numeric
  cells the VLM mis-reads. The two PP-OCRv6 small ONNX models (~31 MB, Apache-2.0, PaddlePaddle's
  own ONNX export) plus their character dictionary ship as `src/main/resources/ocr/` classpath
  resources and are loaded by `OcrModels` lazily via `createSession(bytes)` (env `OCR_MODELS_DIR`
  override for dev). The export carries **no** `character` metadata, so the dictionary is a
  separate file, and `DigitOcr` refuses to load when its length does not match the model's output
  class count (an off-by-one would shift every digit silently); `OcrModelsTest` loads the bundled
  trio on every `gradlew test`. The detector parameters are per model
  (`TextDetector.Params.PP_OCR_V6_SMALL`). `suggestRuntimeModules` is
  UNCHANGED by onnxruntime (it needs no extra jlink module) and its native libs extract to
  `%TEMP%`, NOT the install dir (guardrail 2 — verified under the bundled module set). Models
  are committed to git; build-time fetch is an option if the repo should stay lean.
- Gradle **configuration cache is off on purpose** (Compose jpackage tasks aren't
  cc-safe). Don't enable it.
- **Repositories are `mavenCentral()` + `google()` only** (plus the Plugin Portal for plugins).
  The JetBrains Space `compose/dev` repository was removed on 2026-09-22 (SIB-MOD-02): stable
  Compose resolves from Maven Central, and `dependencies --configuration runtimeClasspath` and
  `buildEnvironment` were byte-identical before and after. Only add a dev repository scoped
  with `content { includeGroupByRegex(...) }`, and only for a pre-release you actually need.
- **Two Compose artifacts are pinned to explicit coordinates**, because their `compose.*` DSL
  accessors are deprecated: `org.jetbrains.compose.material3:material3` (stable since the
  alpha-only days the deprecated `compose.material3` accessor was kept for) and
  `org.jetbrains.compose.components:components-resources` (was `compose.components.resources`).
  Bump BOTH whenever the `org.jetbrains.compose` plugin moves — components-resources tracks the
  plugin version 1:1, while material3's stable line lags it (plugin 1.12.0 ↔ material3 1.9.0), so
  they are not interchangeable. Check the resolved version with
  `gradlew dependencies --configuration runtimeClasspath` rather than assuming.
- **Bundled drawables go through the Compose resources library:** images/SVGs live in
  `src/main/composeResources/drawable/` (lowercase filenames) and are loaded via the generated
  `Res` (`com.basetool.bpextractor.resources`) + `painterResource`, NOT the deprecated
  `androidx.compose.ui.res.useResource`/`loadImageBitmap`/`loadSvgPainter`.

## When you change…

- **the JDK version** → update `jvmToolchain` *and* confirm the `javaHome` toolchain
  resolves to the same JDK; rebuild the MSI and launch the GUI from the app image.
- **the parser/regex or model** → update `sample.log` + the tests; re-confirm the
  179/`greluc` characterization against the local `game-log/`.
- **`Locate`'s colour anchors or the crop geometry** → run `PanelDumpTest` over the whole sample
  corpus and LOOK at the crops, then the full `PromptSmokeTest` sweep. The game restyles the
  refinement terminal without notice, and a broken anchor does not fail — it exports a work order
  read out of the sidebar (see the knowledge base: *The refinery got a new skin and every panel was
  cropped to the sidebar*).
- **bundled modules or the runtime** → `suggestRuntimeModules`, rebuild, GUI-launch test.
- **`onnxruntime`** → diff `OcrDigestTest` across the bump (above), then `suggestRuntimeModules` + a
  GUI-launch test from the app image — the native libs come out of the jar at runtime, so a
  packaging regression shows up at launch, not in the tests.
- **the bundled `/ocr/` models** → the `OcrCandidateEval` round (above) with and without the verify
  model, every disputed cell settled against the pixels, then a new `OcrDigestTest` baseline,
  `suggestRuntimeModules` and a GUI-launch test from the app image.
- **the export shape** → bump `schemaVersion` for any breaking change. Additive optional
  (nullable) fields may stay within the current version (basetool ADR-0008 evolution
  rule — precedents: `capturedAt` on `sourceImages`, 2026-06-11; `additionalSourceFolders`
  on `BlueprintExport`, 2026-06-12); mirror them in the basetool's DTOs/spec and BOTH
  repos' contract tests in the same change.
- **the released version** → don't edit it anywhere by hand; it comes from the git tag
  (see *Releases*). CI sets `project.version`, the `generateBuildInfo` task writes it into
  the generated `BuildInfo.VERSION`, and `BlueprintExtractor.TOOL_VERSION` (the app's
  reported version + the export `toolVersion`) reads that — so the MSI and the app's
  reported version stay in lockstep. The dev fallback in `build.gradle.kts` stays `1.0.0`.

## Releases (CI)

GitHub Actions — [`.github/workflows/ci.yml`](.github/workflows/ci.yml):

- **Push to `main` / PRs / manual dispatch:** `check` (windows) runs `test` +
  `createDistributable` (a packaging smoke test — no MSI/WiX); `workflows` (ubuntu) runs
  actionlint and zizmor over `.github/workflows`, both checksum-/hash-pinned (actionlint's
  SHA-256 in `ci.yml`, zizmor's in `.github/requirements/zizmor.txt` — bumped by hand).
- **Push a `v*` tag (e.g. `v1.2.0`):** after both pass, three jobs — `build-msi` (MSI via
  `package-msi.ps1`, `contents: read`, Gradle cache **disabled**), `attest` (no build code,
  `id-token` + `attestations: write`) and `publish` (VirusTotal + `gh release create`,
  `contents: write`). Suffixed tags (`v1.2.0-rc1`) publish as pre-releases.
- **Supply-chain rules (SIB-SEC-02 / SIB-CI-03, 2026-09-22):** every `uses:` is pinned to a
  full commit SHA with a `# vX.Y.Z` comment; `.github/dependabot.yml` moves those pins and the
  Gradle build weekly; every checkout has `persist-credentials: false`; the tag reaches
  scripts only via `env: REF_NAME` and is validated against
  `^v\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$` first; every job has `timeout-minutes`. There is no
  third-party release action any more (`softprops/action-gh-release` is gone). The one zizmor
  ignore (`cache-poisoning` on the `check` job's cache) is justified in place: nothing that job
  builds is published.

**The release version is the tag, not a constant in the build.** The workflow strips the
leading `v` and exports `APP_VERSION`, which `build.gradle.kts` reads (`System.getenv` →
`-PappVersion` → `1.0.0` dev fallback); the MSI `packageVersion` drops any `-suffix` to
stay numeric `major.minor.build`. To cut a release:

```powershell
git tag v1.2.0 ; git push origin v1.2.0
```

CI builds the MSI through `package-msi.ps1`, so the WiX setup (pinned WiX 7 — installed
or bootstrapped as a local dotnet tool whose package must match a pinned SHA-512,
OSMF-EULA auto-acceptance on CI, Util/UI extensions) is honored on the runner too. The `publish` job is the only one granted
`contents: write`.

**Every release gets a signed build-provenance attestation** (`actions/attest`, SLSA
via Sigstore) over the MSI, so a download can be traced back to the commit and
workflow run that built it (`gh attestation verify <file>.msi --repo krt-profit/basetool-sc-extractor`).
It runs in its own `attest` job on the artifact `build-msi` uploaded (download-artifact v8
fails on a digest mismatch), with `id-token: write` + `attestations: write` and nothing else
— job-level `permissions:` **replaces** the workflow default. It attests the artifact only;
nothing is uploaded anywhere, and public repos get this on every GitHub plan. `publish` runs
only after it, and the release notes tell users how to verify. Provenance proves where the
MSI was built, not that everything that ran in `build-msi` was honest — that is what the SHA
pins are for.

**Every release is scanned on VirusTotal** (`.github/scripts/virustotal-scan.ps1`) and the
release notes link the report plus the MSI's SHA-256 — the answer to the recurring
`Trojan:Script/Wacatac.H!ml` false positive Defender raises on the unsigned MSI (unsigned +
zero reputation + an embedded PowerShell updater string reads as a dropper to an ML
classifier). Load-bearing details:

- It runs on VirusTotal's **free Public API**, which is only free under two conditions this
  repo meets and a future change could break: it "must not be used in commercial products
  or services" (GPL-3.0 community tool, nothing is sold) and "must not be used in business
  workflows that do not contribute new files" (we upload a new MSI every time). Quotas are
  500 req/day and **4 req/min** — that is why the poll interval must stay ≥ 15s.
- **Everything uploaded to the Public API becomes public on VirusTotal.** Only the MSI —
  already a public release asset — may ever be passed to it. Never a `Game.log`, never a
  refinery screenshot (guardrails 1 / 1a).
- **The scan never gates the release.** A detection does not fail the build (the known
  Defender FP would block every release), and any VT problem degrades to an empty
  `release_note` output and exit 0. Requires the `VIRUSTOTAL_API_KEY` repo secret; without
  it the step warns and the release publishes with the generated notes alone.
- Files > 32 MB (ours is one) must go through `GET /files/upload_url`, not `POST /files`.
  VT doesn't document that endpoint's tier; verified 2026-08-19 that a free Public API key
  gets a `bigfiles.virustotal.com` URL from it. (Premium has its own `/private/…` variant.)

## Repo / publishing

- Public repo: `https://github.com/krt-profit/basetool-sc-extractor` (branch `main`).
- `.github/CODEOWNERS` routes review requests to `@greluc` (catch-all plus explicit
  entries for `.github/`, `package-msi.ps1` and `net/` + `update/`). It only *blocks* a
  merge if `main`'s branch protection has "Require review from Code Owners" on.
- **License: GPL-3.0-or-later** (`LICENSE`). Deps are permissive (Apache-2.0/BSD); the
  bundled JRE is GPLv2 + Classpath Exception (redistribution OK, does not infect app
  code). The bundled font (Lato) ships its OFL text under
  `src/main/resources/fonts/` — if you add/replace a bundled font, add its license too.
- Gitignored and kept out of the published repo: `game-log/` (private logs), `.claude/`
  (the design skill), `_extracted/` (reference material), `tools/`, `build/`, `dist/`,
  `_gui_*`. Verify `game-log/` is absent from any commit before pushing.
- Commit/push only when the user asks.
