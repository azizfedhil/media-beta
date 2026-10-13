package com.mediahub.pear.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.LibrarySource
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.PearCapsuleButton
import com.mediahub.pear.shell.PearSwitch
import com.mediahub.pear.shell.SegmentedControl
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.launch

@Composable
fun LibrarySourceControls(modifier: Modifier = Modifier) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    val prefs = env.data.libraryPrefs
    val simkl = env.data.simkl
    val connected = simkl.isConnected
    val source = prefs.source(connected)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Where the library lives: this device, or Simkl (only when Simkl is connected).
        SegmentedControl(
            labels = listOf("This device", "Simkl"),
            selectedIndex = if (source == LibrarySource.Local) 0 else 1,
            onSelect = { i ->
                if (i == 0) {
                    prefs.choose(LibrarySource.Local)
                } else if (connected) {
                    prefs.choose(LibrarySource.Simkl)
                    scope.launch { simkl.sync() }
                }
            },
        )

        if (connected) {
            val expl = if (source == LibrarySource.Local) {
                "Your own library and watch tracking on this device. Nothing is sent to Simkl."
            } else {
                "Simkl's lists are your library, and what you watch is scrobbled to Simkl."
            }
            PearText(expl, style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PearText("Keep in sync automatically", style = PearTheme.type.subheadline)
                PearSwitch(checked = prefs.autoSync, onCheckedChange = { prefs.setAutoSync(it) })
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PearCapsuleButton(
                    label = if (prefs.isSyncing) "Syncing…" else "Sync now",
                    symbol = "arrow.triangle.2.circlepath",
                    onClick = {
                        // A sync already running is not started again.
                        if (!prefs.isSyncing) {
                            scope.launch {
                                prefs.sync(simkl, env.data.library, env.data.history, force = true)
                            }
                        }
                    },
                )

                prefs.report?.let { report ->
                    PearText(report, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
                } ?: prefs.lastSync?.let { last ->
                    val minAgo = (epochMillis() - last) / 60_000
                    val agoText = if (minAgo <= 1) "just now" else "$minAgo min ago"
                    PearText("Last synced $agoText", style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
                }
            }
        } else {
            PearText(
                "Using the library on this device. Connect Simkl in Integrations to use it as your library or to sync with it.",
                style = PearTheme.type.footnote,
                color = PearTheme.colors.textSecondary,
            )
        }
    }
}
