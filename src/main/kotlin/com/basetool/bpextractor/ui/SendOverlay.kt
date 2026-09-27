package com.basetool.bpextractor.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.net.Codes
import com.basetool.bpextractor.net.auth.NoPersistentKeyException
import com.basetool.bpextractor.net.auth.UnboundTokenException
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.i18n.SendStrings
import kotlinx.coroutines.CoroutineScope

/**
 * The plain-language text for a failed send or sync: what the code means and what fixes it, else the
 * clock hint, else the server's detail.
 *
 * @param strings the send strings of the active language
 * @param error the failure
 * @return the text to show
 */
fun sendErrorText(strings: SendStrings, error: SendState.Error): String =
    when (error.code) {
        Codes.CLIENT_NOT_ALLOWED, Codes.CLIENT_SUSPENDED -> strings.errorClientNotAllowed(error.message)
        Codes.CLIENT_VERSION_UNSUPPORTED -> strings.errorVersionUnsupported(error.message)
        Codes.INSTALLATION_REVOKED, Codes.CLIENT_REVOKED -> strings.errorRevoked(error.message)
        Codes.SCOPE_MISSING -> strings.errorScopeMissing(error.message)
        NoPersistentKeyException.CODE -> strings.errorNoPersistentKey
        UnboundTokenException.CODE -> strings.errorTokenNotBound
        else ->
            if (error.clockOffsetSeconds != 0L) {
                strings.errorClockSkew(error.clockOffsetSeconds, error.message)
            } else {
                strings.error(error.message)
            }
    }

/**
 * The "An Basetool senden" scrim modal that walks the user through consent, browser approval, sending
 * and result, driven by [SendController.state]; hidden in [SendState.Idle].
 *
 * @param controller the send state machine and actions
 * @param appScope the UI scope the flow runs on
 * @param onSaveLocally optional save-as-JSON fallback shown on the error screen; `null` hides it
 */
@Composable
fun SendOverlay(
    controller: SendController,
    appScope: CoroutineScope,
    onSaveLocally: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val state = controller.state
    if (state is SendState.Idle) return

    val title =
        when (state) {
            is SendState.Authenticating -> strings.send.authTitle
            is SendState.Done -> strings.send.resultTitle
            else -> strings.send.consentTitle
        }

    KrtModal(
        title = title,
        onDismiss = { controller.dismiss() },
        body = {
            when (state) {
                is SendState.Consent -> {
                    Text(strings.send.consentBody, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                    InstallationLabelField(strings.send, state.label, state.labelInvalid) { controller.editLabel(it) }
                }
                is SendState.Authenticating -> AuthenticatingBody(strings.send, state.userCode, state.reason)
                is SendState.Sending ->
                    Text(strings.send.inProgress, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                is SendState.Done ->
                    Text(strings.send.resultBody, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                is SendState.Error ->
                    Text(sendErrorText(strings.send, state), style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                is SendState.Idle -> {}
            }
        },
        footer = {
            when (state) {
                is SendState.Consent -> {
                    GhostButton(strings.cancel, onClick = { controller.dismiss() })
                    Spacer(Modifier.weight(1f))
                    CtaButton(strings.send.consentConfirm, onClick = { controller.confirmConsent(appScope) })
                }
                is SendState.Authenticating -> {
                    GhostButton(strings.send.authOpenBrowser, onClick = { controller.reopenBrowser() })
                    Spacer(Modifier.weight(1f))
                    GhostButton(strings.cancel, onClick = { controller.dismiss() })
                }
                is SendState.Sending -> Spacer(Modifier.height(1.dp))
                is SendState.Done -> {
                    GhostButton(strings.close, onClick = { controller.dismiss() })
                    Spacer(Modifier.weight(1f))
                    CtaButton(strings.send.openInBasetool, onClick = { controller.openResult() })
                }
                is SendState.Error -> {
                    if (onSaveLocally != null) {
                        GhostButton(
                            strings.send.saveLocally,
                            onClick = {
                                controller.dismiss()
                                onSaveLocally()
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    GhostButton(strings.close, onClick = { controller.dismiss() })
                }
                is SendState.Idle -> {}
            }
        },
    )
}
