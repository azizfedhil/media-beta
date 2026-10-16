package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.DesktopClipboard
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.weight

/** A report of how the last run ended badly: a title for the card, and the full text (the briefing). */
class CrashReport(val title: String, val text: String)

/** Where the card gets its report. The JVM builds provide one that reads the log folder. */
interface CrashReportSource {
    /** The report from the last run, or null. */
    fun pending(): CrashReport?

    /** Marks the pending report as seen, so it is not shown again. */
    fun dismiss()

    /** Whether the platform can open the logs folder in the file manager. */
    val canOpenFolder: Boolean
        get() = false

    /** Opens the logs folder; returns false when that did not work. */
    fun openFolder(): Boolean = false
}

object CrashReports {
    var source: CrashReportSource? = null
}

/**
 * The card that tells the person the app closed unexpectedly last time, with the briefing and three buttons: copy it, open
 * the logs folder, or dismiss it. Shows nothing when there is no report. It never blocks the app.
 */
@Composable
fun CrashBriefingHost() {
    val source = CrashReports.source
    val report = remember { runCatching { source?.pending() }.getOrNull() }
    var dismissed by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    if (source == null || report == null || dismissed) return

    val c = PearTheme.colors
    val summary = remember(report) { report.text.lineSequence().firstOrNull().orEmpty().take(200) }
    val briefing = remember(report) { report.text.lines().take(40).joinToString("\n") }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = interaction, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp)
                .fillMaxWidth(0.9f)
                .background(c.surface, RoundedCornerShape(16.dp))
                .border(1.dp, c.outline, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PearText("Pear. closed unexpectedly last time", style = PearTheme.type.headline.weight(FontWeight.SemiBold))
            PearText(summary, style = PearTheme.type.footnote, color = c.textSecondary, maxLines = 2)
            Column(
                Modifier.fillMaxWidth()
                    .height(260.dp)
                    .background(c.fill, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                PearText(
                    briefing,
                    style = PearTheme.type.caption.copy(fontFamily = FontFamily.Monospace),
                    color = c.textSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PearCapsuleButton(label = "Copy report", style = PearButtonStyle.Accent, onClick = { DesktopClipboard.copy(report.text) })
                if (source.canOpenFolder) {
                    PearCapsuleButton(label = "Open logs folder", onClick = { source.openFolder() })
                }
                PearCapsuleButton(
                    label = "Dismiss",
                    onClick = {
                        runCatching { source.dismiss() }
                        dismissed = true
                    },
                )
            }
        }
    }
}
