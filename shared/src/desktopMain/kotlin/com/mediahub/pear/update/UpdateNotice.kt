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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.detail.DownloadBar
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.model.StreamSize
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalHttp
import com.mediahub.pear.shell.PearButtonStyle
import com.mediahub.pear.shell.PearCapsuleButton
import com.mediahub.pear.shell.Route
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** The key of this machine's installer in `latest.json` (see PearUpdates.kt), or null where Pear has no installer. */
internal fun updatePlatformKey(osName: String = System.getProperty("os.name").orEmpty()): String? {
    val os = osName.lowercase()
    return when {
        "win" in os -> "windows-x64"
        "linux" in os -> "linux-x64-rpm"
        else -> null
    }
}

/** The release manifest URL for the repo `owner/name`, or null when no repo is set. Kept for the manifest reader (PearUpdates.kt). */
internal fun latestManifestUrl(repo: String): String? {
    val r = repo.trim().trim('/')
    return if (r.isEmpty()) null else "https://github.com/$r/releases/latest/download/latest.json"
}

/** How often the app checks again while it runs. */
private const val RECHECK_MILLIS = 6L * 60 * 60 * 1000

/** How long the "Opening the installer" note stays before it goes away. */
private const val OPENED_NOTE_MILLIS = 4_000L

/** Where the notice is in an update. No phase at all (null) means no card. */
private sealed interface NoticePhase {
    val update: ReleaseDecision.Available

    /** The installer is coming down in the background. */
    data class Downloading(override val update: ReleaseDecision.Available) : NoticePhase

    /** The installer is on disk and checked: the person can update now, or later. */
    data class Ready(override val update: ReleaseDecision.Available, val file: File) : NoticePhase

    /** The installer is being started. */
    data class Opening(override val update: ReleaseDecision.Available) : NoticePhase

    /** The installer would not start. [file] is where it is saved, so Try again starts that file, not a new download. */
    data class Failed(override val update: ReleaseDecision.Available, val message: String, val file: File) : NoticePhase
}

/** Bytes so far of a download, and the total (-1 while it is unknown). */
private data class DownloadProgress(val done: Long, val total: Long) {
    val fraction: Double? get() = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else null
}

private fun downloadText(p: DownloadProgress): String {
    val fraction = p.fraction
    return if (fraction != null) {
        "Downloading… ${(fraction * 100).toInt()}%"
    } else {
        "Downloading… ${StreamSize.format(p.done)}"
    }
}

/**
 * Checks for a release with a higher version number a few seconds after launch and then every six hours. When there is
 * one, its installer downloads in the background with a progress bar; then the card offers Update now / Later. Update now
 * runs the installer, and on Windows the app quits so the installer can replace it ([onQuit] does the normal shutdown).
 * Later hides the card until the next launch. Nothing shows when the app is up to date, offline, or a background download
 * fails (it is tried again at the next check). The card stays off the player, and a download keeps going while it is hidden.
 */
@Composable
fun BoxScope.UpdateNotice(onQuit: () -> Unit = {}) {
    val http = LocalHttp.current
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<NoticePhase?>(null) }
    var progress by remember { mutableStateOf(DownloadProgress(0L, -1L)) }
    var dismissed by remember { mutableStateOf(false) }

    fun install(update: ReleaseDecision.Available, file: File) {
        phase = NoticePhase.Opening(update)
        scope.launch {
            val opened = withContext(Dispatchers.IO) { openInstaller(file) }
            if (opened.isSuccess) {
                if (installerReplacesApp()) {
                    onQuit() // the installer takes over from here and starts Pear again
                } else {
                    delay(OPENED_NOTE_MILLIS)
                    phase = null
                }
            } else {
                PearLog.w("Update", "the installer did not open", opened.exceptionOrNull())
                phase = NoticePhase.Failed(update, "Saved to ${file.absolutePath}. Open it to install.", file)
            }
        }
    }

    LaunchedEffect(Unit) {
        val repo = PearBuild.UPDATE_REPO.trim().trim('/')
        val platform = updatePlatformKey() ?: return@LaunchedEffect
        if (repo.isEmpty()) return@LaunchedEffect
        delay(5_000) // not during the launch intro
        while (true) {
            // Once there is a card (downloading, ready, opening, failed) or the person said Later, the check leaves it alone.
            if (phase == null && !dismissed) {
                when (val decision = checkForRelease(http, repo, PearBuild.VERSION, platform)) {
                    is ReleaseDecision.Available -> {
                        phase = NoticePhase.Downloading(decision)
                        progress = DownloadProgress(0L, -1L)
                        try {
                            var lastShown = 0L
                            val file = downloadUpdate(decision.asset) { done, total ->
                                // About ten updates a second: a progress bar does not need more, and each one redraws the card.
                                val time = System.currentTimeMillis()
                                if (done == total || time - lastShown >= 100L) {
                                    lastShown = time
                                    progress = DownloadProgress(done, total)
                                }
                            }
                            phase = NoticePhase.Ready(decision, file)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Quiet: the next check tries again.
                            PearLog.w("Update", "the update did not download", e)
                            phase = null
                        }
                    }
                    is ReleaseDecision.UpToDate -> Unit
                    is ReleaseDecision.Unknown -> PearLog.i("Update", "no update check: ${decision.reason}")
                }
            }
            delay(RECHECK_MILLIS)
        }
    }

    val shown = phase ?: return
    if (dismissed || env.shell.currentNav().top is Route.Player) return
    val update = shown.update
    val c = PearTheme.colors
    val failed = shown is NoticePhase.Failed
    val replaces = installerReplacesApp()

    val title = when (shown) {
        is NoticePhase.Downloading -> "Downloading Pear ${update.version}"
        is NoticePhase.Ready -> "Pear ${update.version} is ready"
        is NoticePhase.Opening -> if (replaces) "Installing…" else "Opening the installer…"
        is NoticePhase.Failed -> "The update did not finish"
    }
    val detail = when (shown) {
        is NoticePhase.Downloading -> downloadText(progress)
        is NoticePhase.Ready ->
            if (replaces) "You have ${PearBuild.VERSION}. Pear closes and opens again to finish." else "You have ${PearBuild.VERSION}."
        is NoticePhase.Opening -> if (replaces) "Pear opens again in a moment." else "The installer opens in its own window."
        is NoticePhase.Failed -> shown.message
    }
    val symbol = when (shown) {
        is NoticePhase.Failed -> "exclamationmark.triangle.fill"
        is NoticePhase.Opening -> "arrow.up.right.square"
        else -> "arrow.down.circle"
    }

    Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
        Column(
            Modifier.widthIn(max = 340.dp)
                .background(c.surface, RoundedCornerShape(16.dp))
                .border(1.dp, c.outline, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PearIcon(symbol, size = 20.dp, tint = if (failed) c.red else c.accent)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PearText(title, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                    PearText(detail, style = PearTheme.type.footnote, color = c.textSecondary)
                }
            }
            if (shown is NoticePhase.Downloading) {
                progress.fraction?.let { DownloadBar(it) }
            }
            when (shown) {
                is NoticePhase.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearCapsuleButton(label = "Update now", style = PearButtonStyle.Accent, onClick = { install(update, shown.file) })
                    PearCapsuleButton(
                        label = "Later",
                        onClick = {
                            dismissed = true
                            phase = null
                        },
                    )
                }
                is NoticePhase.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearCapsuleButton(label = "Try again", style = PearButtonStyle.Accent, onClick = { install(update, shown.file) })
                    PearCapsuleButton(
                        label = "Later",
                        onClick = {
                            dismissed = true
                            phase = null
                        },
                    )
                }
                is NoticePhase.Downloading, is NoticePhase.Opening -> Unit
            }
        }
    }
}
