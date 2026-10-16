package com.mediahub.pear.simkl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.data.DesktopClipboard
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.openUrl
import com.mediahub.pear.player.ui.PlayerSpinner
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.PearButtonStyle
import com.mediahub.pear.shell.PearCapsuleButton
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.launch

/**
 * "Connect to Simkl" controls with PIN / device-flow sign-in (spec: SimklConnect.swift).
 * Used in IntegrationsScreen, SimklLoginScreen, and ProfileSheet.
 */
@Composable
fun SimklConnectControls(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val env = LocalAppEnv.current
    val simkl = env.data.simkl
    val scope = rememberCoroutineScope()
    val c = PearTheme.colors

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (simkl.isConnected) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PearIcon("checkmark.circle.fill", size = 16.dp, tint = Color(0xFF34C759))
                PearText(
                    "Connected to Simkl" + (simkl.account?.name?.let { " as $it" } ?: ""),
                    style = if (compact) PearTheme.type.subheadline.weight(FontWeight.SemiBold) else PearTheme.type.body.weight(FontWeight.SemiBold),
                    color = Color(0xFF34C759),
                )
            }

            simkl.syncError?.let { err ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PearIcon("exclamationmark.triangle.fill", size = 14.dp, tint = c.orange)
                    PearText(err, style = PearTheme.type.footnote, color = c.orange)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PearCapsuleButton(
                    label = if (simkl.isSyncing) "Syncing…" else "Sync now",
                    symbol = "arrow.triangle.2.circlepath",
                    onClick = {
                        if (!simkl.isSyncing) {
                            scope.launch { simkl.sync(force = true) }
                        }
                    },
                )
                if (!compact) {
                    PearCapsuleButton(
                        label = "Disconnect",
                        style = PearButtonStyle.Destructive,
                        onClick = { simkl.disconnect() },
                    )
                }
            }
        } else if (simkl.pin != null) {
            val pin = simkl.pin!!
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PearText("1. Open this page on any device", style = PearTheme.type.footnote, color = c.textSecondary)
                PearText(
                    pin.verificationUri,
                    style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
                    color = c.accent,
                    modifier = Modifier.clickable { openUrl(pin.verificationUriComplete ?: pin.verificationUri) },
                )

                PearText("2. Enter this code", style = PearTheme.type.footnote, color = c.textSecondary)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PearText(
                        pin.userCode,
                        style = PearTheme.type.largeTitle.weight(FontWeight.Bold).copy(fontSize = 32.sp, letterSpacing = 2.sp),
                        color = c.textPrimary,
                    )
                    PearCapsuleButton(
                        label = "Copy",
                        symbol = "copy",
                        onClick = { DesktopClipboard.copy(pin.userCode) },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val urlToOpen = pin.verificationUriComplete ?: pin.verificationUri
                    PearCapsuleButton(
                        label = "Open Simkl",
                        symbol = "safari",
                        style = PearButtonStyle.Accent,
                        onClick = { openUrl(urlToOpen) },
                    )
                    PearCapsuleButton(
                        label = "Cancel",
                        onClick = { simkl.cancelLogin() },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerSpinner(diameter = 16.dp)
                    PearText(simkl.loginStatus ?: "Waiting for authorisation…", style = PearTheme.type.footnote, color = c.textSecondary)
                }
            }
        } else {
            PearCapsuleButton(
                label = "Connect to Simkl",
                symbol = "link",
                style = PearButtonStyle.Accent,
                onClick = { simkl.connect() },
            )

            simkl.loginStatus?.let { status ->
                PearText(status, style = PearTheme.type.footnote, color = c.textSecondary)
            }
        }

        simkl.loginError?.let { err ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PearIcon("octagon-x", size = 14.dp, tint = c.red)
                PearText(err, style = PearTheme.type.footnote, color = c.red)
            }
        }
    }
}

/**
 * Dedicated Simkl login screen reached from ProfileSheet.
 */
@Composable
fun SimklLoginScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = LocalBottomInset.current

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                SimklConnectControls()
            }

            PearText(
                "Tap Connect to Simkl, then enter the code at simkl.com/pin. No account setup, keys or redirects needed. Your library syncs when the app opens and only downloads what changed.",
                style = PearTheme.type.footnote,
                color = PearTheme.colors.textSecondary,
            )
        }

        GlassTopBar(title = "Simkl", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}