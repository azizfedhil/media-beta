package com.mediahub.pear.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.openUrl
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.shell.PearButtonStyle
import com.mediahub.pear.shell.PearCapsuleButton
import com.mediahub.pear.shell.Route
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay

/** The key of this machine's installer in `latest.json`, or null where Pear has no installer (the check is skipped). */
internal fun updatePlatformKey(osName: String = System.getProperty("os.name").orEmpty()): String? {
    val os = osName.lowercase()
    return when {
        "win" in os -> "windows-x64"
        "linux" in os -> "linux-x64-rpm"
        else -> null
    }
}

/** Where the app reads the release list from, for the repo `owner/name`. Null when no repo is set. */
internal fun latestManifestUrl(repo: String): String? {
    val r = repo.trim().trim('/')
    return if (r.isEmpty()) null else "https://github.com/$r/releases/latest/download/latest.json"
}

/**
 * Checks for a newer release a few seconds after launch, and if there is one, shows a small notice in the bottom-right
 * corner: "Pear X is available", with a button to the release page and a "Later" button. Nothing shows when the app is
 * up to date or the check fails (offline). It stays off the player, and "Later" hides it until the next launch.
 * Windows and Linux do not update themselves: the button opens the release page, and the user runs the new installer.
 */
@Composable
fun BoxScope.UpdateNotice() {
    val http = LocalHttp.current
    val env = LocalAppEnv.current
    var found by remember { mutableStateOf<UpdateDecision.Available?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val manifest = latestManifestUrl(PearBuild.UPDATE_REPO) ?: return@LaunchedEffect
        val platform = updatePlatformKey() ?: return@LaunchedEffect
        delay(5_000) // not during the launch intro
        found = checkForUpdate(http, manifest, PearBuild.VERSION, platform) as? UpdateDecision.Available
    }

    val update = found ?: return
    if (dismissed || env.shell.currentNav().top is Route.Player) return
    val c = PearTheme.colors

    Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
        Column(
            Modifier.widthIn(max = 340.dp)
                .background(c.surface, RoundedCornerShape(16.dp))
                .border(1.dp, c.outline, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PearIcon("arrow.down.circle", size = 20.dp, tint = c.accent)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PearText("Pear ${update.version} is available", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                    PearText("You have ${PearBuild.VERSION}.", style = PearTheme.type.footnote, color = c.textSecondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PearCapsuleButton(
                    label = "View update",
                    style = PearButtonStyle.Accent,
                    onClick = { openUrl(update.releaseUrl.ifEmpty { update.download?.url.orEmpty() }) },
                )
                PearCapsuleButton(label = "Later", onClick = { dismissed = true })
            }
        }
    }
}