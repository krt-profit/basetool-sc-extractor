package com.basetool.bpextractor.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.net.SyncReport
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.i18n.SyncStrings
import kotlinx.coroutines.CoroutineScope

/**
 * The "Blueprints synchronisieren" scrim modal: the one-time opt-in, browser approval, the account-check
 * warning, progress and the report, driven by [SyncController.state]; hidden in [SyncState.Idle].
 *
 * @param controller the sync state machine and actions
 * @param appScope the UI scope the sync runs on
 */
@Composable
fun SyncOverlay(controller: SyncController, appScope: CoroutineScope) {
    val strings = LocalStrings.current
    val state = controller.state
    if (state is SyncState.Idle) return

    val title =
        when (state) {
            is SyncState.Authenticating -> strings.send.authTitle
            is SyncState.AccountMismatch -> strings.sync.mismatchTitle
            is SyncState.CheckingAccount, is SyncState.Syncing -> strings.sync.workingTitle
            is SyncState.Done -> strings.sync.resultTitle
            else -> strings.sync.consentTitle
        }

    KrtModal(
        title = title,
        onDismiss = { controller.dismiss() },
        body = {
            when (state) {
                is SyncState.Consent -> {
                    Text(strings.sync.consentBody, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                    state.label?.let { label ->
                        InstallationLabelField(strings.send, label, state.labelInvalid) { controller.editLabel(it) }
                    }
                }
                is SyncState.Authenticating -> AuthenticatingBody(strings.send, state.userCode, state.reason)
                is SyncState.CheckingAccount ->
                    Text(strings.sync.checkingAccount, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                is SyncState.Syncing ->
                    Text(strings.sync.syncing, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                is SyncState.AccountMismatch ->
                    Text(strings.sync.mismatchBody(state.handle), style = MaterialTheme.typography.bodyMedium, color = Krt.White)
                is SyncState.Done -> ReportBody(strings.sync, state)
                is SyncState.Error ->
                    Text(sendErrorText(strings.send, state.error), style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                is SyncState.Idle -> {}
            }
        },
        footer = {
            when (state) {
                is SyncState.Consent -> {
                    GhostButton(strings.cancel, onClick = { controller.dismiss() })
                    Spacer(Modifier.weight(1f))
                    CtaButton(strings.sync.consentConfirm, onClick = { controller.confirmConsent(appScope) })
                }
                is SyncState.Authenticating -> {
                    GhostButton(strings.send.authOpenBrowser, onClick = { controller.reopenBrowser() })
                    Spacer(Modifier.weight(1f))
                    GhostButton(strings.cancel, onClick = { controller.dismiss() })
                }
                is SyncState.CheckingAccount, is SyncState.Syncing -> Spacer(Modifier.height(1.dp))
                is SyncState.AccountMismatch -> {
                    GhostButton(strings.sync.mismatchContinue, onClick = { controller.continueDespiteMismatch(appScope) })
                    Spacer(Modifier.weight(1f))
                    CtaButton(strings.cancel, onClick = { controller.dismiss() })
                }
                is SyncState.Done -> {
                    if (state.report?.removedElsewhere?.isNotEmpty() == true) {
                        GhostButton(strings.sync.overrideButton, onClick = { controller.addRemovedElsewhere(appScope) })
                    }
                    Spacer(Modifier.weight(1f))
                    CtaButton(strings.close, onClick = { controller.dismiss() })
                }
                is SyncState.Error -> {
                    Spacer(Modifier.weight(1f))
                    GhostButton(strings.close, onClick = { controller.dismiss() })
                }
                is SyncState.Idle -> {}
            }
        },
    )
}

/** The report of a finished sync: the counts, then each list of names that needs the member. */
@Composable
private fun ReportBody(strings: SyncStrings, state: SyncState.Done) {
    val report: SyncReport? = state.report
    if (report == null) {
        Text(strings.resultNothing, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
    } else {
        Text(strings.resultAdded(report.added, report.alreadyOwned), style = MaterialTheme.typography.bodyMedium, color = Krt.White)
        NameList(strings.resultRemovedElsewhere(report.removedElsewhere.size), report.removedElsewhere.map { it.name })
        NameList(strings.resultUnmatched(report.unmatched.size), report.unmatched)
        NameList(strings.resultAmbiguous(report.ambiguous.size), report.ambiguous)
        NameList(strings.resultRefused(report.refused.size), report.refused.map { (name, reason) -> "$name ($reason)" })
    }
    if (state.unknownAccount) {
        Text(strings.resultUnknownAccount, style = MaterialTheme.typography.bodySmall, color = Krt.Orange)
    }
}

/** A heading and its names, or nothing when there are none. */
@Composable
private fun NameList(heading: String, names: List<String>) {
    if (names.isEmpty()) return
    Text(heading, style = MaterialTheme.typography.bodySmall, color = Krt.Orange)
    Text(names.joinToString("\n") { "· $it" }, style = MaterialTheme.typography.bodySmall, color = Krt.Gray1)
}
