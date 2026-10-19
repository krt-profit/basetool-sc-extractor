package com.basetool.bpextractor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.CoroutineScope

/**
 * The compact "remember me" status line in the Start footer: „Mit Basetool verbunden" with a
 * „Vom Basetool trennen" action when a token is stored, else „Nicht mit Basetool verbunden".
 *
 * @param account the account state and disconnect action
 */
@Composable
fun AccountStatusRow(account: AccountController) {
    val strings = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(if (account.connected) Krt.Success else Krt.ControlBorder)
        Text(
            if (account.connected) strings.account.connected else strings.account.disconnected,
            style = MaterialTheme.typography.bodySmall,
            color = if (account.connected) Krt.Gray1 else Krt.TextMuted,
        )
        if (account.connected) {
            Spacer(Modifier.size(4.dp))
            GhostButton(
                strings.account.disconnect,
                onClick = { account.requestDisconnect() },
                enabled = !account.working,
                tone = ButtonTone.QUIET_DANGER,
            )
        }
    }
}

/**
 * The disconnect-confirmation modal, explaining that disconnecting revokes the stored login; hidden
 * unless [AccountController.confirming] is set.
 *
 * @param account the account state and disconnect action
 * @param scope the UI coroutine scope the disconnect runs on
 */
@Composable
fun AccountDisconnectOverlay(account: AccountController, scope: CoroutineScope) {
    if (!account.confirming) return
    val strings = LocalStrings.current
    KrtModal(
        title = strings.account.disconnectTitle,
        onDismiss = { account.cancelDisconnect() },
        danger = true,
        body = {
            Text(strings.account.disconnectBody, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
        },
        footer = {
            GhostButton(strings.cancel, onClick = { account.cancelDisconnect() })
            DangerButton(strings.account.disconnectConfirm, onClick = { account.confirmDisconnect(scope) })
        },
    )
}
