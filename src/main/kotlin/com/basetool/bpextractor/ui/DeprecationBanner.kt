package com.basetool.bpextractor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/** The VerseKit site that takes over the extractor's workflows and the Basetool sync. */
const val VERSEKIT_URL = "https://versekit.xharig.com/"

/**
 * The start screen's end-of-life notice: the extractor is no longer developed and VerseKit offers its
 * workflows and the Basetool sync. It cannot be dismissed; the button opens [VERSEKIT_URL] in the
 * browser and is left out when the platform cannot browse.
 */
@Composable
fun DeprecationBanner(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val canBrowse = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) }
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hudBox(hairline = Krt.Orange, bracket = Krt.Orange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(Krt.Orange)
        Column(Modifier.weight(1f)) {
            Text(strings.deprecationTitle.uppercase(), style = MaterialTheme.typography.labelMedium, color = Krt.Orange)
            Spacer(Modifier.height(3.dp))
            Text(strings.deprecationBody, style = MaterialTheme.typography.bodyMedium, color = Krt.White)
            Spacer(Modifier.height(3.dp))
            Text(VERSEKIT_URL, style = KrtDataStyle, color = Krt.Gray1)
        }
        if (canBrowse) {
            GhostButton(strings.deprecationOpen, tone = ButtonTone.OUTLINE, onClick = {
                scope.launch(Dispatchers.IO) { runCatching { Desktop.getDesktop().browse(URI(VERSEKIT_URL)) } }
            })
        }
    }
}
