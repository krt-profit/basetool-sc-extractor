package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.CoroutineScope

/**
 * The compact "remember me" status line (epic krt-iri/basetool#639, #648), shown in the Start
 * footer: an orange dot + „Mit Basetool verbunden" + a „Vom Basetool trennen" action when a token
 * is stored, or a muted „Nicht mit Basetool verbunden" otherwise. Pairs with
 * [AccountDisconnectOverlay], which renders the confirmation above the page.
 *
 * @param account the account state + disconnect action
 */
@Composable
fun AccountStatusRow(account: AccountController) {
    val strings = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).background(if (account.connected) Krt.Orange else Krt.Gray3))
        if (account.connected) {
            Text(
                strings.account.connected,
                style = MaterialTheme.typography.bodySmall,
                color = Krt.Gray2,
            )
            Spacer(Modifier.size(4.dp))
            GhostButton(
                strings.account.disconnect,
                onClick = { account.requestDisconnect() },
                enabled = !account.working,
            )
        } else {
            Text(
                strings.account.disconnected,
                style = MaterialTheme.typography.bodySmall,
                color = Krt.Gray3,
            )
        }
    }
}

/**
 * The disconnect-confirmation scrim modal (no native dialog, per the design system): explains that
 * disconnecting revokes the stored login so the next send asks for approval again, then confirms or
 * cancels. Hidden unless [AccountController.confirming] is set.
 *
 * @param account the account state + disconnect action
 * @param scope the UI coroutine scope the disconnect runs on
 */
@Composable
fun AccountDisconnectOverlay(account: AccountController, scope: CoroutineScope) {
    if (!account.confirming) return
    val strings = LocalStrings.current
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(Krt.Black.copy(alpha = 0.8f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { account.cancelDisconnect() },
                )
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val swallow = remember { MutableInteractionSource() }
        Column(
            modifier =
                Modifier.widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .background(Krt.Black.copy(alpha = 0.97f))
                    .border(1.dp, Krt.Orange)
                    .clickable(interactionSource = swallow, indication = null, onClick = {}),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Krt.Gray4)
                        .drawBehind {
                            drawLine(
                                Krt.Orange,
                                Offset(0f, size.height),
                                Offset(size.width, size.height),
                                2.dp.toPx(),
                            )
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(
                    strings.account.disconnectTitle.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                )
            }
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    strings.account.disconnectBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Krt.Gray1,
                )
            }
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Krt.Gray4.copy(alpha = 0.55f))
                        .drawBehind {
                            drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton(strings.cancel, onClick = { account.cancelDisconnect() })
                Spacer(Modifier.weight(1f))
                CtaButton(
                    strings.account.disconnectConfirm,
                    onClick = { account.confirmDisconnect(scope) },
                )
            }
        }
    }
}
