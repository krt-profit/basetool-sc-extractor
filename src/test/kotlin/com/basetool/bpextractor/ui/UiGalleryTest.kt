package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.basetool.bpextractor.AppState
import com.basetool.bpextractor.BlueprintExtractor
import com.basetool.bpextractor.ExtractorScreen
import com.basetool.bpextractor.ToastInfo
import com.basetool.bpextractor.blueprintStep
import com.basetool.bpextractor.model.BlueprintEvent
import com.basetool.bpextractor.model.BlueprintScan
import com.basetool.bpextractor.model.LogAccount
import com.basetool.bpextractor.net.PendingAdd
import com.basetool.bpextractor.net.SyncReport
import com.basetool.bpextractor.net.auth.LoginReason
import com.basetool.bpextractor.refinery.ExtractWarning
import com.basetool.bpextractor.refinery.GpuInfo
import com.basetool.bpextractor.refinery.HardwareSnapshot
import com.basetool.bpextractor.refinery.HardwareTier
import com.basetool.bpextractor.refinery.ImageOutcome
import com.basetool.bpextractor.refinery.OllamaModel
import com.basetool.bpextractor.refinery.PipelineResult
import com.basetool.bpextractor.refinery.PipelineStage
import com.basetool.bpextractor.refinery.RegistryVersion
import com.basetool.bpextractor.refinery.TierDecision
import com.basetool.bpextractor.refinery.ValidatedOrder
import com.basetool.bpextractor.refinery.model.RefineryExtract
import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import com.basetool.bpextractor.refinery.model.RefineryExtractImage
import com.basetool.bpextractor.refinery.model.RefineryExtractOrder
import com.basetool.bpextractor.resources.Res
import com.basetool.bpextractor.resources.basetool_extractor_logo
import com.basetool.bpextractor.resources.made_by_the_community_black
import com.basetool.bpextractor.ui.i18n.Lang
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.i18n.stringsFor
import com.basetool.bpextractor.ui.refinery.AlertBox
import com.basetool.bpextractor.ui.refinery.ChipTone
import com.basetool.bpextractor.ui.refinery.HelpOverlay
import com.basetool.bpextractor.ui.refinery.KrtChip
import com.basetool.bpextractor.ui.refinery.KrtRadio
import com.basetool.bpextractor.ui.refinery.ModelTask
import com.basetool.bpextractor.ui.refinery.OllamaStatus
import com.basetool.bpextractor.ui.refinery.PanelCard
import com.basetool.bpextractor.ui.refinery.RefineryImage
import com.basetool.bpextractor.ui.refinery.RefineryUiState
import com.basetool.bpextractor.ui.refinery.TierBar
import com.basetool.bpextractor.ui.refinery.UnsavedChangesOverlay
import com.basetool.bpextractor.update.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test

/**
 * Dev scaffolding, not a behaviour test: renders every screen, sub-step, modal and state of the GUI
 * offscreen to `<UI_GALLERY_OUT>/<name>.png` for a visual check against the design system. Trivially
 * green unless `UI_GALLERY_OUT` is set; the output stays outside the repo.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class UiGalleryTest {

    private val idle = CoroutineScope(Job().apply { cancel() })

    @Test
    fun `render the ui gallery`() {
        val out = System.getenv("UI_GALLERY_OUT")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        out.mkdirs()
        val only = System.getenv("UI_GALLERY_ONLY")?.takeUnless { it.isBlank() }?.split(',')?.map { it.trim() }
        val fixtures = Fixtures(File(out, "_fixtures"))
        val shots = shots(fixtures)
        shots.filter { only == null || only.any { prefix -> it.name.startsWith(prefix) } }.forEach { shot ->
            render(out, shot)
            println("  ${shot.name}.png")
        }
    }

    private class Shot(
        val name: String,
        val width: Int = 1180,
        val height: Int = 820,
        val lang: Lang = Lang.DE,
        val interact: (ImageComposeScene) -> Unit = {},
        val content: @Composable () -> Unit,
    )

    private fun render(out: File, shot: Shot) {
        ImageComposeScene(shot.width, shot.height, Density(1f)) {
            KrtTheme {
                CompositionLocalProvider(LocalStrings provides stringsFor(shot.lang)) { shot.content() }
            }
        }.use { scene ->
            var t = 0L
            repeat(4) {
                scene.render(t)
                t += 50_000_000
                Thread.sleep(40)
            }
            shot.interact(scene)
            repeat(6) {
                scene.render(t)
                t += 50_000_000
                Thread.sleep(40)
            }
            val image = scene.render(t)
            File(out, "${shot.name}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Composable
    private fun AppFrame(
        tab: MainTab,
        lang: Lang = Lang.DE,
        stepLabels: List<String>? = null,
        stepIndex: Int = 0,
        maxReached: Int = 0,
        overlay: @Composable () -> Unit = {},
        body: @Composable () -> Unit,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(Krt.Canvas).border(1.dp, Krt.Hairline)) {
                KrtTitleBarContent(
                    icon = painterResource(Res.drawable.basetool_extractor_logo),
                    title = "Basetool SC Extractor",
                    maximized = false,
                    onMinimize = {},
                    onToggleMaximize = {},
                    onClose = {},
                    actions = { LanguageToggle(lang) {} },
                )
                CommandStrip(tab = tab, onTab = {}, stepLabels = stepLabels, stepIndex = stepIndex, maxReached = maxReached)
                Box(Modifier.weight(1f).fillMaxWidth()) { body() }
                CommunityDisclaimerFooter(painterResource(Res.drawable.made_by_the_community_black))
            }
            overlay()
        }
    }

    @Composable
    private fun StartFrame(update: UpdateUiState = UpdateUiState.Hidden, lang: Lang = Lang.DE) {
        AppFrame(MainTab.START, lang) { StartScreen(update, {}, {}, {}) }
    }

    @Composable
    private fun BlueprintFrame(state: AppState, lang: Lang = Lang.DE, overlay: @Composable () -> Unit = {}) {
        val strings = LocalStrings.current
        AppFrame(MainTab.BLUEPRINTS, lang, strings.bpSteps, blueprintStep(state), 0, overlay) {
            ExtractorScreen(state, idle)
        }
    }

    @Composable
    private fun RefineryFrame(state: RefineryUiState, lang: Lang = Lang.DE, overlay: @Composable () -> Unit = {}) {
        val strings = LocalStrings.current
        AppFrame(MainTab.REFINERY, lang, strings.rfSteps, state.step, state.maxReached, overlay) {
            RefineryScreen(state, idle) {}
        }
    }

    private fun shots(f: Fixtures): List<Shot> {
        val update = UpdateInfo("2.11.0", "v2.11.0", "https://example.invalid/x.msi", 58_000_000, "0".repeat(64))
        val list = mutableListOf<Shot>()

        list += Shot("01-start") { StartFrame() }
        list += Shot("01-start-en", lang = Lang.EN) { StartFrame(lang = Lang.EN) }
        list += Shot("01-start-small", 640, 520) { StartFrame() }
        list += Shot("02-update-available") { StartFrame(UpdateUiState.Available(update)) }
        list += Shot("02-update-downloading") { StartFrame(UpdateUiState.Downloading(update, 21_000_000, 58_000_000)) }
        list += Shot("02-update-installing") { StartFrame(UpdateUiState.Installing(update)) }
        list += Shot("02-update-failed") { StartFrame(UpdateUiState.Failed(update, "HTTP 503 Service Unavailable")) }
        list += Shot("03-start-hover-card", interact = { it.move(300f, 330f) }) { StartFrame() }

        list += Shot("10-bp-config-empty") { BlueprintFrame(f.bpState(channel = "")) }
        list += Shot("10-bp-config-valid") { BlueprintFrame(f.bpState(channel = f.live.path)) }
        list += Shot("10-bp-config-valid-en", lang = Lang.EN) { BlueprintFrame(f.bpState(channel = f.live.path), Lang.EN) }
        list += Shot("10-bp-config-archive") { BlueprintFrame(f.bpState(channel = f.archive.path)) }
        list += Shot("10-bp-config-wrong") { BlueprintFrame(f.bpState(channel = f.wrong.path)) }
        list += Shot("10-bp-config-missing") { BlueprintFrame(f.bpState(channel = f.root.path + "\\gibt-es-nicht")) }
        list += Shot("10-bp-config-error") {
            val s = f.bpState(channel = "")
            val strings = LocalStrings.current
            s.channelError = strings.bpErrSelectChannel
            s.isError = true
            s.status = strings.bpStatusFixFields
            BlueprintFrame(s)
        }
        list += Shot("10-bp-config-small", 640, 520) { BlueprintFrame(f.bpState(channel = f.live.path)) }
        list += Shot("11-bp-running-start") {
            val s = f.bpState(channel = f.live.path)
            s.running = true
            s.status = LocalStrings.current.bpStatusSearching
            BlueprintFrame(s)
        }
        list += Shot("11-bp-running-progress") {
            val s = f.bpState(channel = f.live.path)
            s.running = true
            s.progressDone = 8
            s.progressTotal = 13
            s.bytesDone = 61
            s.bytesTotal = 100
            s.status = LocalStrings.current.bpStatusProcessing(8, 13, "Game Build(11518367) 26 Sep 26 (20 51 03).log")
            BlueprintFrame(s)
        }
        list += Shot("12-bp-summary") { BlueprintFrame(f.bpSummary()) }
        list += Shot("12-bp-summary-en", lang = Lang.EN) { BlueprintFrame(f.bpSummary(), Lang.EN) }
        list += Shot("12-bp-summary-small", 640, 520) { BlueprintFrame(f.bpSummary()) }
        list += Shot("12-bp-summary-saved") { BlueprintFrame(f.bpSummary(saved = true)) }
        list += Shot("12-bp-summary-warnings") {
            val s = f.bpSummary(accounts = 2)
            s.skippedFiles = listOf("Game.log")
            s.isError = true
            s.status = LocalStrings.current.cannotOpen("blueprints.json", "Zugriff verweigert")
            BlueprintFrame(s)
        }
        list += Shot("12-bp-summary-zero") { BlueprintFrame(f.bpSummary(empty = true)) }
        list += Shot("13-bp-toast-success") {
            val s = f.bpSummary()
            s.toast = ToastInfo(LocalStrings.current.bpToastDoneTitle, LocalStrings.current.bpToastDoneBody(31), error = false)
            BlueprintFrame(s)
        }
        list += Shot("13-bp-toast-error") {
            val s = f.bpState(channel = f.wrong.path)
            s.toast = ToastInfo(LocalStrings.current.bpToastNoLogsTitle, LocalStrings.current.bpToastNoLogsBody, error = true)
            BlueprintFrame(s)
        }

        val sendStates = listOf(
            "consent" to SendState.Consent("Gaming-PC"),
            "consent-invalid" to SendState.Consent("PC-01.local", labelInvalid = true),
            "auth" to SendState.Authenticating("WDJB-MJHT", "https://profit-base.online/auth/device", LoginReason.NONE),
            "auth-upgrade" to SendState.Authenticating("WDJB-MJHT", "https://profit-base.online/auth/device", LoginReason.KEY_UPGRADE),
            "sending" to SendState.Sending,
            "done" to SendState.Done("https://profit-base.online/"),
            "error" to SendState.Error(code = "QUOTA_EXCEEDED", status = 429, message = "quota", retryAfterSeconds = 3600, reference = "01J9ZK3Q8X"),
        )
        sendStates.forEach { (name, st) ->
            list += Shot("20-send-$name") {
                val c = SendController().also { it.state = st }
                BlueprintFrame(f.bpSummary(), overlay = { SendOverlay(c, idle, onSaveLocally = {}) })
            }
        }
        list += Shot("20-send-consent-small", 640, 520) {
            val c = SendController().also { it.state = SendState.Consent("Gaming-PC") }
            BlueprintFrame(f.bpSummary(), overlay = { SendOverlay(c, idle, onSaveLocally = {}) })
        }
        val report = SyncReport(
            added = 12,
            alreadyOwned = 17,
            removedElsewhere = listOf(PendingAdd("bt-1", "Arrowhead Sniper Rifle", f.anyItem())),
            unmatched = listOf("Sth/2/C Cirrus"),
            ambiguous = listOf("Palatino Core Daystar"),
            refused = listOf("Yubarev \"Mirage\" Pistol" to "LOCKED"),
        )
        val syncStates = listOf(
            "consent" to SyncState.Consent("Gaming-PC"),
            "consent-nolabel" to SyncState.Consent(null),
            "auth" to SyncState.Authenticating("WDJB-MJHT", "https://profit-base.online/auth/device", LoginReason.SCOPE_UPGRADE),
            "checking" to SyncState.CheckingAccount,
            "syncing" to SyncState.Syncing,
            "mismatch" to SyncState.AccountMismatch("PLAYER_A"),
            "unconfirmed" to SyncState.AccountUnconfirmed("PLAYER_A"),
            "done" to SyncState.Done(report),
            "done-nothing" to SyncState.Done(null),
            "error" to SyncState.Error(SendState.Error(code = "CLIENT_VERSION_UNSUPPORTED", status = 403, message = "2.10.0", reference = "01J9ZK3Q8X")),
        )
        syncStates.forEach { (name, st) ->
            list += Shot("21-sync-$name") {
                val c = SyncController().also { it.state = st }
                BlueprintFrame(f.bpSummary(), overlay = { SyncOverlay(c, idle) })
            }
        }
        list += Shot("22-account-disconnect") {
            val account = AccountController().also {
                it.connected = true
                it.confirming = true
            }
            AppFrame(MainTab.START, overlay = { AccountDisconnectOverlay(account, idle) }) { StartScreen(UpdateUiState.Hidden, {}, {}, {}) }
        }

        list += Shot("30-picker-folder") {
            BlueprintFrame(f.bpState(channel = f.live.path), overlay = {
                FilePickerDialog(PickerMode.FOLDER, "Channel-Ordner wählen", "Diesen Ordner verwenden", f.live.path, {}, {})
            })
        }
        list += Shot("30-picker-save") {
            BlueprintFrame(f.bpState(channel = f.live.path), overlay = {
                FilePickerDialog(PickerMode.SAVE_FILE, "JSON speichern unter", "Speichern", f.existingJson.path, {}, {})
            })
        }
        list += Shot("30-picker-save-small", 640, 520) {
            BlueprintFrame(f.bpState(channel = f.live.path), overlay = {
                FilePickerDialog(PickerMode.SAVE_FILE, "JSON speichern unter", "Speichern", f.newJson.path, {}, {})
            })
        }
        list += Shot("30-picker-save-en", lang = Lang.EN) {
            BlueprintFrame(f.bpState(channel = f.live.path), Lang.EN, overlay = {
                FilePickerDialog(PickerMode.SAVE_FILE, "Save JSON as", "Save", f.newJson.path, {}, {})
            })
        }

        list += Shot("40-rf-preflight-checking") { RefineryFrame(f.rf()) }
        list += Shot("40-rf-preflight-ready") { RefineryFrame(f.rf(OllamaStatus.Ready, HardwareTier.RECOMMENDED)) }
        list += Shot("40-rf-preflight-ready-en", lang = Lang.EN) { RefineryFrame(f.rf(OllamaStatus.Ready, HardwareTier.RECOMMENDED), Lang.EN) }
        list += Shot("40-rf-preflight-missing-min") { RefineryFrame(f.rf(OllamaStatus.ModelMissing("qwen3-vl:4b-instruct"), HardwareTier.MINIMUM)) }
        list += Shot("40-rf-preflight-pulling") { RefineryFrame(f.rf(OllamaStatus.Pulling(2_100_000_000, 6_100_000_000, "pulling"), HardwareTier.MINIMUM)) }
        list += Shot("40-rf-preflight-pullfailed") { RefineryFrame(f.rf(OllamaStatus.PullFailed("connection reset"), HardwareTier.RECOMMENDED)) }
        list += Shot("40-rf-preflight-unreachable-cpu") { RefineryFrame(f.rf(OllamaStatus.Unreachable("refused"), HardwareTier.CPU, scRunning = true)) }
        list += Shot("40-rf-preflight-small", 640, 520) { RefineryFrame(f.rf(OllamaStatus.Ready, HardwareTier.RECOMMENDED)) }
        list += Shot("41-rf-models-verify-active") { RefineryFrame(f.rfModels(verify = true)) }
        list += Shot("41-rf-models-verify-pulling") {
            RefineryFrame(f.rfModels(task = ModelTask.Pulling("qwen3.5:9b", 2_400_000_000, 6_600_000_000, "pulling")))
        }
        list += Shot("41-rf-models-verify-failed") {
            RefineryFrame(f.rfModels(task = ModelTask.Failed("qwen3.5:9b", removal = false, message = "connection reset")))
        }
        list += Shot("41-rf-models-remove") { RefineryFrame(f.rfModels(verify = true, fallbackInstalled = true)) }
        list += Shot("41-rf-models-remove-en", lang = Lang.EN) { RefineryFrame(f.rfModels(verify = true, fallbackInstalled = true), Lang.EN) }
        list += Shot("41-rf-models-remove-failed") {
            RefineryFrame(
                f.rfModels(
                    verify = true,
                    fallbackInstalled = true,
                    task = ModelTask.Failed("qwen3-vl:4b-instruct", removal = true, message = "model is in use"),
                ),
            )
        }
        list += Shot("41-rf-models-offer-and-remove-small", 640, 520) { RefineryFrame(f.rfModels(fallbackInstalled = true)) }
        list += Shot("41-rf-models-update") { RefineryFrame(f.rfModels(verify = true, primaryOutdated = true)) }
        list += Shot("41-rf-models-update-en", lang = Lang.EN) { RefineryFrame(f.rfModels(verify = true, primaryOutdated = true), Lang.EN) }
        list += Shot("41-rf-models-updating") {
            RefineryFrame(
                f.rfModels(
                    verify = true,
                    primaryOutdated = true,
                    task = ModelTask.Pulling("qwen3-vl:8b-instruct", 3_900_000_000, 6_200_000_000, "pulling"),
                ),
            )
        }
        list += Shot("41-rf-models-everything") {
            RefineryFrame(f.rfModels(fallbackInstalled = true, primaryOutdated = true))
        }
        list += Shot("41-rf-help") { RefineryFrame(f.rf(OllamaStatus.Ready, HardwareTier.RECOMMENDED), overlay = { HelpOverlay {} }) }
        list += Shot("41-rf-help-small", 640, 520) { RefineryFrame(f.rf(OllamaStatus.Ready, HardwareTier.RECOMMENDED), overlay = { HelpOverlay {} }) }

        list += Shot("42-rf-images-empty") { RefineryFrame(f.rf(step = 1)) }
        list += Shot("42-rf-images-loaded") { RefineryFrame(f.rf(step = 1, images = true)) }
        list += Shot("42-rf-images-loaded-en", lang = Lang.EN) { RefineryFrame(f.rf(step = 1, images = true), Lang.EN) }
        list += Shot("42-rf-images-none-selected") { RefineryFrame(f.rf(step = 1, images = true, noneSelected = true)) }
        list += Shot("42-rf-images-loading") { RefineryFrame(f.rf(step = 1).also { it.loadingImages = true; it.folder = f.root.path }) }
        list += Shot("42-rf-images-nofiles") { RefineryFrame(f.rf(step = 1).also { it.folder = f.wrong.path; it.loadedFolder = f.wrong.path }) }
        list += Shot("42-rf-images-watch") { RefineryFrame(f.rf(step = 1, images = true).also { it.setClipboardWatch(true) }) }
        list += Shot("42-rf-images-scrolled", interact = { it.wheel(600f, 500f, 20f) }) { RefineryFrame(f.rf(step = 1, images = true)) }
        list += Shot("42-rf-images-small", 640, 520) { RefineryFrame(f.rf(step = 1, images = true)) }

        list += Shot("43-rf-extract-running") { RefineryFrame(f.rfExtract(running = true)) }
        list += Shot("43-rf-extract-done") { RefineryFrame(f.rfExtract(running = false)) }
        list += Shot("43-rf-extract-error") { RefineryFrame(f.rfExtract(running = false, error = "Ollama antwortete mit HTTP 500")) }
        list += Shot("43-rf-extract-cancelled") { RefineryFrame(f.rfExtract(running = false, error = RefineryUiState.CANCELLED_MARKER)) }

        list += Shot("44-rf-review") { RefineryFrame(f.rfReview()) }
        list += Shot("44-rf-review-en", lang = Lang.EN) { RefineryFrame(f.rfReview(), Lang.EN) }
        list += Shot("44-rf-review-clean") { RefineryFrame(f.rfReview(warnings = emptySet())) }
        list += Shot("44-rf-review-small", 640, 520) { RefineryFrame(f.rfReview()) }
        list += Shot("44-rf-review-edit-row", interact = { it.click(1130f, 448f) }) { RefineryFrame(f.rfReview()) }
        list += Shot("44-rf-review-edit-header", interact = { it.click(278f, 287f) }) { RefineryFrame(f.rfReview()) }
        list += Shot("45-rf-review-unsaved") { RefineryFrame(f.rfReview(), overlay = { UnsavedChangesOverlay({}, {}) }) }

        list += Shot("46-rf-export") { RefineryFrame(f.rfReview().also { it.step = 4; it.maxReached = 4 }) }
        list += Shot("46-rf-export-en", lang = Lang.EN) { RefineryFrame(f.rfReview().also { it.step = 4; it.maxReached = 4 }, Lang.EN) }
        list += Shot("46-rf-export-saved") {
            RefineryFrame(f.rfReview().also { it.step = 4; it.maxReached = 4; it.exportedFile = f.existingJson })
        }
        list += Shot("46-rf-export-blocked") {
            RefineryFrame(f.rfReview(missingQty = true).also { it.step = 4; it.maxReached = 4; it.exportError = "Zugriff verweigert: C:\\Program Files\\x.json" })
        }
        list += Shot("46-rf-export-small", 640, 520) { RefineryFrame(f.rfReview().also { it.step = 4; it.maxReached = 4 }) }
        list += Shot("47-rf-export-send-consent") {
            val c = SendController().also { it.state = SendState.Consent("Gaming-PC") }
            RefineryFrame(f.rfReview().also { it.step = 4; it.maxReached = 4 }, overlay = { SendOverlay(c, idle, onSaveLocally = {}) })
        }

        list += Shot("90-components") { ComponentSheet() }
        list += Shot("91-hover-cta", interact = { it.move(95f, 131f) }) { ComponentSheet() }
        list += Shot("91-hover-ghost", interact = { it.move(256f, 131f) }) { ComponentSheet() }
        list += Shot("91-hover-danger", interact = { it.move(566f, 131f) }) { ComponentSheet() }
        list += Shot("91-hover-outline", interact = { it.move(414f, 131f) }) { ComponentSheet() }
        list += Shot("91-hover-segment", interact = { it.move(180f, 246f) }) { ComponentSheet() }
        list += Shot("91-hover-tab", interact = { it.move(140f, 64f) }) { ComponentSheet() }
        (1..12).forEach { n -> list += Shot("92-focus-tab$n", interact = { it.tab(n) }) { ComponentSheet() } }
        return list
    }

    @Composable
    private fun ComponentSheet() {
        AppFrame(MainTab.START) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CtaButton("Primär", {}, Modifier.width(150.dp))
                    GhostButton("Ghost", {}, Modifier.width(150.dp))
                    GhostButton("Outline", {}, Modifier.width(140.dp), tone = ButtonTone.OUTLINE)
                    GhostButton("Löschen", {}, Modifier.width(140.dp), tone = ButtonTone.QUIET_DANGER)
                    DangerButton("Trennen", {})
                    CtaButton("Aus", {}, enabled = false)
                    GhostButton("Aus", {}, enabled = false)
                    IconGhostButton("✎", "edit", {})
                    IconGhostButton("✕", "remove", {}, tone = ButtonTone.QUIET_DANGER)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    KrtCheckbox(true, {}, "Aktiv")
                    KrtCheckbox(false, {}, "Inaktiv")
                    KrtCheckbox(false, {}, "Gesperrt", enabled = false)
                    KrtRadio(true, "Gewählt", {})
                    KrtRadio(false, "Nicht gewählt", {})
                    KrtSwitch(true, {}, "An")
                    KrtSwitch(false, {}, "Aus")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KrtSegmented(listOf("Kommend", "Vergangen", "Alle"), 0, {})
                    KrtSegmented(listOf("DE", "EN"), 1, {}, compact = true)
                    CountBadge(31)
                    ChipTone.entries.forEach { KrtChip(it.name, it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KrtTextField("C:\\Games\\StarCitizen\\LIVE", {}, "Pfad", Modifier.width(260.dp))
                    KrtTextField("", {}, "Platzhalter …", Modifier.width(200.dp), supportingText = "Hinweis")
                    KrtTextField("X:\\nix", {}, "", Modifier.width(220.dp), isError = true, supportingText = "Ordner nicht gefunden")
                    KrtTextField("gesperrt", {}, "", Modifier.width(160.dp), enabled = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlertBox(Krt.Success) { Text("Erfolg", color = Krt.Gray1) }
                        AlertBox(Krt.Warning) { Text("Warnung", color = Krt.Gray1) }
                        AlertBox(Krt.Danger) { Text("Fehler", color = Krt.Gray1) }
                        AlertBox(Krt.Info) { Text("Info", color = Krt.Gray1) }
                    }
                    Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KrtProgressBar(0.62f)
                        LoadingLine("Lädt …")
                        TierBar(listOf("CPU", "MIN", "EMPF"), 1)
                        DotNote(Krt.Success, "OK")
                        DotNote(Krt.ControlBorder, "Leerlauf", Krt.TextMuted)
                    }
                    PanelCard("Panel", Krt.Success, Modifier.width(260.dp)) {
                        Text("Inhalt", style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                    }
                    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KrtToast("Fertig", "31 Blueprints exportiert.")
                        KrtToast("Fehler", "Keine Logs gefunden.", error = true)
                    }
                }
            }
        }
    }

    private fun ImageComposeScene.move(x: Float, y: Float) {
        sendPointerEvent(PointerEventType.Enter, Offset(x, y))
        sendPointerEvent(PointerEventType.Move, Offset(x, y))
    }

    private fun ImageComposeScene.click(x: Float, y: Float) {
        move(x, y)
        sendPointerEvent(PointerEventType.Press, Offset(x, y), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, Offset(x, y), button = PointerButton.Primary)
    }

    private fun ImageComposeScene.wheel(x: Float, y: Float, lines: Float) {
        move(x, y)
        sendPointerEvent(PointerEventType.Scroll, Offset(x, y), scrollDelta = Offset(0f, lines))
    }

    private fun ImageComposeScene.tab(times: Int) {
        repeat(times) {
            sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyDown))
            sendKeyEvent(KeyEvent(Key.Tab, KeyEventType.KeyUp))
        }
    }

    /** Synthetic folders, files and states — nothing here comes from a real log or capture. */
    private class Fixtures(val root: File) {
        val live = File(root, "StarCitizen/LIVE")
        val hotfix = File(root, "StarCitizen/HOTFIX")
        val archive = File(root, "archiv")
        val wrong = File(root, "leer")
        val existingJson = File(root, "StarCitizen/blueprints.json")
        val newJson = File(root, "StarCitizen/neu.json")

        init {
            listOf(live, hotfix).forEach { dir ->
                File(dir, "logbackups").mkdirs()
                File(dir, "Game.log").writeText("")
            }
            archive.mkdirs()
            File(archive, "Game Build(11518367) 26 Sep 26.log").writeText("")
            wrong.mkdirs()
            existingJson.writeText("{}")
        }

        fun anyItem() = BlueprintExtractor.envelopeOf(bpSummary().resultExport!!).items.first()

        fun bpState(channel: String): AppState = AppState().also {
            it.channelFolder = channel
            it.outputFile = existingJson.path
        }

        fun bpSummary(accounts: Int = 1, empty: Boolean = false, saved: Boolean = false): AppState {
            val names = listOf(
                "Yubarev \"Mirage\" Pistol" to "Weapon",
                "Sth/2/C Cirrus" to "Ship Component",
                "Palatino Core Daystar" to "Armor",
                "Arrowhead Sniper Rifle" to "Weapon",
                "Morozov-SH Helmet Crusader Edition" to "Armor",
                "P8-AR Rifle Magazine (30 cap)" to "Ammunition",
                "FS-9 LMG" to "Weapon",
                "Venture Legs Rust Society" to "Armor",
            )
            val events = if (empty) emptyList() else (0 until 31).map { i ->
                val (n, c) = names[i % names.size]
                BlueprintEvent(
                    productName = n,
                    category = c,
                    receivedAt = "2026-09-%02dT%02d:%02d:00.000Z".format(1 + i % 28, 10 + i % 12, i % 60),
                    player = "PLAYER_A",
                    notificationId = i,
                    sourceFile = "Game.log",
                )
            }
            val scan = BlueprintScan(live.path, listOf(hotfix.path), 12, events)
            return bpState(live.path).also {
                it.resultExport = scan
                it.resultAccounts = listOf(LogAccount("PLAYER_A", 12, events.size)) +
                    if (accounts > 1) listOf(LogAccount("PLAYER_B", 2, 0), LogAccount(null, 1, 0)) else emptyList()
                it.selectedAccount = it.resultAccounts.first()
                it.resultSummary = "ok"
                if (saved) it.resultFile = existingJson
            }
        }

        fun rf(
            status: OllamaStatus = OllamaStatus.Checking,
            tier: HardwareTier? = null,
            scRunning: Boolean = false,
            step: Int = 0,
            images: Boolean = false,
            noneSelected: Boolean = false,
        ): RefineryUiState = RefineryUiState().also { s ->
            s.runPreflight(CoroutineScope(Job().apply { cancel() }))
            s.ollamaStatus = status
            if (tier != null) {
                s.hardware = HardwareSnapshot(scRunning, 34_359_738_368, GpuInfo("NVIDIA GeForce RTX 4070", 12_884_901_888))
                s.decision = TierDecision(tier, "qwen3-vl:8b-instruct", tier != HardwareTier.CPU, if (tier == HardwareTier.CPU) 31 else 9)
            }
            s.step = step
            s.maxReached = step
            if (images) {
                s.folder = live.path
                s.loadedFolder = live.path
                listOf(
                    Triple("auftrag-01_setup.png", 3840 to 2160, false),
                    Triple("auftrag-01_setup_2.png", 3840 to 2160, false),
                    Triple("panel-crop.png", 1046 to 1288, true),
                    Triple("klein.png", 1280 to 720, false),
                ).forEachIndexed { i, (name, size, pre) ->
                    s.images += RefineryImage(
                        file = File(live, name),
                        width = size.first,
                        height = size.second,
                        precropped = pre,
                        thumbnail = thumb(i),
                        selected = !noneSelected && i != 3,
                    )
                }
            }
        }

        fun rfModels(
            verify: Boolean = false,
            fallbackInstalled: Boolean = false,
            task: ModelTask? = null,
            primaryOutdated: Boolean = false,
        ): RefineryUiState = rf(OllamaStatus.Ready, HardwareTier.RECOMMENDED).also { s ->
            s.verifyAvailable = verify
            s.installedExtractorModels = listOfNotNull(
                OllamaModel("qwen3-vl:8b-instruct", 6_100_000_000, null, "0533d74"),
                OllamaModel("qwen3.5:9b", 6_600_000_000, null, "56671c2").takeIf { verify },
                OllamaModel("qwen3-vl:4b-instruct", 3_300_000_000, null, "ee4b975").takeIf { fallbackInstalled },
            )
            if (primaryOutdated) s.registryVersions["qwen3-vl:8b-instruct"] = RegistryVersion("70e58e37", 6_200_000_000)
            s.modelTask = task
        }

        fun rfExtract(running: Boolean, error: String? = null): RefineryUiState = rf(OllamaStatus.Ready, HardwareTier.RECOMMENDED, step = 2, images = true).also { s ->
            s.maxReached = 2
            s.runImages += s.images.take(3)
            s.running = running
            s.currentIndex = if (running) 1 else -1
            s.stageReached[0] = PipelineStage.READ
            s.stageReached[1] = PipelineStage.NORMALIZE
            s.outcomes[0] = ImageOutcome("auftrag-01_setup.png", "located", quoted = false, rows = 7)
            if (!running) {
                s.stageReached[1] = PipelineStage.READ
                s.stageReached[2] = PipelineStage.READ
                s.outcomes[1] = ImageOutcome("auftrag-01_setup_2.png", "located", quoted = true, rows = 6)
                s.outcomes[2] = ImageOutcome("panel-crop.png", "precropped", quoted = null, rows = 0)
            }
            s.console += listOf(
                "▶ Locate — auftrag-01_setup.png: Panel bei 1712,388 (1046×1288)",
                "▶ Normalize — 2× hochskaliert, Kontrast gestreckt",
                "▶ Read — qwen3-vl:8b-instruct · 7 Zeilen",
                "⚠ Auftrag wurde vor GET QUOTE aufgenommen",
                "▶ Locate — auftrag-01_setup_2.png",
            )
            s.extractError = error
            if (!running && error == null) s.result = review(emptySet(), false).result
        }

        fun rfReview(warnings: Set<ExtractWarning> = setOf(ExtractWarning.SUM_MISMATCH, ExtractWarning.IMPLAUSIBLE_CELL), missingQty: Boolean = false): RefineryUiState =
            review(warnings, missingQty).also { s ->
                s.step = 3
                s.maxReached = 3
                s.ollamaStatus = OllamaStatus.Ready
                s.decision = TierDecision(HardwareTier.RECOMMENDED, "qwen3-vl:8b-instruct", true, 9)
                s.images += RefineryImage(File(live, "a.png"), 3840, 2160, false, thumb(0))
                s.folder = live.path
            }

        private fun review(warnings: Set<ExtractWarning>, missingQty: Boolean): RefineryUiState {
            val goods = listOf(
                RefineryExtractGood(0, "QUANTANIUM (RAW)", 712, 76, 61, true, 0.97, "a.png"),
                RefineryExtractGood(1, "GOLD (ORE)", 553, if (missingQty) null else 46, 21, true, 0.82, "a.png"),
                RefineryExtractGood(2, "INERT MATERIALS", 0, 312, null, false, 0.95, "a.png"),
                RefineryExtractGood(3, "LARANITE (ORE)", 489, 128, 98, true, 0.61, "a.png"),
                RefineryExtractGood(4, "UCTION SALVAGE", 0, 17, null, false, 0.93, "a.png"),
            )
            val order = RefineryExtractOrder(
                panelType = "SETUP",
                quoted = true,
                layoutConfidence = 0.93,
                rawLocationName = "ARC-L1 Wide Forest Station",
                rawMethodName = "DINYX SOLVENTATION",
                rawInManifestTotal = 579,
                rawToRefineTotal = 250,
                expenses = 48_928.0,
                durationMinutes = 188,
                sourceImages = listOf(RefineryExtractImage("a.png", 3840, 2160, "located")),
                goods = goods,
            )
            val extract = RefineryExtract(
                tool = "basetool-sc-extractor",
                toolVersion = "2.11.0",
                model = "qwen3-vl:8b-instruct",
                generatedAt = "2026-10-19T18:42:00Z",
                orders = listOf(order),
            )
            val validated = ValidatedOrder(goods, true, order.rawMethodName, 579, 250, 48_928.0, 188, 0.93, warnings)
            return RefineryUiState().also { it.result = PipelineResult(extract, validated, order.rawLocationName, emptyList()) }
        }

        private fun thumb(seed: Int) = BufferedImage(240, 135, BufferedImage.TYPE_INT_RGB).let { img ->
            val g = img.createGraphics()
            g.color = AwtColor(18, 26, 34)
            g.fillRect(0, 0, 240, 135)
            g.color = AwtColor(40 + seed * 20, 120, 150)
            g.fillRect(150, 15, 70, 105)
            g.color = AwtColor(220, 220, 220)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
            g.drawString("SETUP", 158, 32)
            repeat(6) { r -> g.fillRect(158, 42 + r * 12, 50, 3) }
            g.dispose()
            img.toComposeImageBitmap()
        }
    }
}
