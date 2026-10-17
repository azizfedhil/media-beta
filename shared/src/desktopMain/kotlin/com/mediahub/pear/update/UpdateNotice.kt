package com.mediahub.pear.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.detail.DownloadBar
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.model.StreamSize
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** A newer release is here: the person can update now, or later. */
    data class Offer(override val update: ReleaseDecision.Available) : NoticePhase

    data class Downloading(override val update: ReleaseDecision.Available) : NoticePhase

    /** The installer is being handed to the system. */
    data class Opening(override val update: ReleaseDecision.Available) : NoticePhase

    /** [savedAt] is set when the installer was downloaded but would not open: Try again then opens that file, not a new download. */
    data class Failed(
        override val update: ReleaseDecision.Available,
        val message: String,
        val savedAt: File?,
    ) : NoticePhase
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

/** Remembers [update]'s installer as the one this machine has, so the same build is not offered again. */
private fun rememberInstalled(prefs: PrefStore, update: ReleaseDecision.Available) {
    prefs.setString(PrefKeys.UPDATE_SEEN_HASH, update.fingerprint)
    prefs.setString(PrefKeys.UPDATE_SEEN_VERSION, update.version)
}

/**
 * Checks for a newer release a few seconds after launch and then every six hours, and shows a small notice in the bottom-right
 * corner. A newer release is offered on every launch until the app catches up. A re-uploaded installer of the current version
 * (a new build under the same number) is offered once. Update downloads the installer, checks its SHA-256 when GitHub gives
 * one, and runs it; Later hides the notice until the next launch. Nothing shows when the app is up to date or the check fails
 * (offline). The notice stays off the player, and a download keeps going while it is hidden.
 */
@Composable
fun BoxScope.UpdateNotice() {
    val http = LocalHttp.current
    val env = LocalAppEnv.current
    val prefs = env.data.prefs
    // The scope outlives the early return below, so a download keeps going while the card is hidden over the player.
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<NoticePhase?>(null) }
    var progress by remember { mutableStateOf(DownloadProgress(0L, -1L)) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    fun openInstallerFile(update: ReleaseDecision.Available, file: File) {
        phase = NoticePhase.Opening(update)
        scope.launch {
            val opened = withContext(Dispatchers.IO) { openInstaller(file) }
            if (opened.isSuccess) {
                // The installer is running. This build is now the release, so it is not offered again.
                rememberInstalled(prefs, update)
                delay(OPENED_NOTE_MILLIS)
                phase = null
            } else {
                PearLog.w("Update", "the installer did not open", opened.exceptionOrNull())
                phase = NoticePhase.Failed(update, "Saved to ${file.absolutePath}. Open it to install.", file)
            }
        }
    }

    fun startDownload(update: ReleaseDecision.Available) {
        phase = NoticePhase.Downloading(update)
        progress = DownloadProgress(0L, -1L)
        downloadJob = scope.launch {
            try {
                var lastShown = 0L
                val file = downloadUpdate(update.asset) { done, total ->
                    // About ten updates a second: a progress bar does not need more, and each one redraws the card.
                    val time = System.currentTimeMillis()
                    if (done == total || time - lastShown >= 100L) {
                        lastShown = time
                        progress = DownloadProgress(done, total)
                    }
                }
                openInstallerFile(update, file)
            } catch (e: CancellationException) {
                throw e // Cancel has already put the offer back on screen.
            } catch (e: Exception) {
                PearLog.w("Update", "the update did not download", e)
                phase = NoticePhase.Failed(update, "Couldn't download it (${e.message ?: "unknown error"}).", null)
            }
        }
    }

    fun cancelDownload(update: ReleaseDecision.Available) {
        downloadJob?.cancel()
        downloadJob = null
        phase = NoticePhase.Offer(update)
    }

    LaunchedEffect(Unit) {
        val repo = PearBuild.UPDATE_REPO.trim().trim('/')
        val extension = installerExtension(updatePlatformKey()) ?: return@LaunchedEffect
        if (repo.isEmpty()) return@LaunchedEffect
        delay(5_000) // not during the launch intro
        while (true) {
            // A download or an open in progress is left alone; the check picks up again on the next round.
            val showing = phase
            if (showing == null || showing is NoticePhase.Offer) {
                val decision = checkForRelease(
                    http = http,
                    repo = repo,
                    current = PearBuild.VERSION,
                    extension = extension,
                    seenHash = prefs.string(PrefKeys.UPDATE_SEEN_HASH),
                    seenVersion = prefs.string(PrefKeys.UPDATE_SEEN_VERSION),
                )
                when (decision) {
                    is ReleaseDecision.Available -> {
                        if (!dismissed && (phase == null || phase is NoticePhase.Offer)) {
                            phase = NoticePhase.Offer(decision)
                            // A re-upload of this build is recorded once it is on screen, so it is offered only once.
                            if (decision.sameVersion) prefs.setString(PrefKeys.UPDATE_SEEN_HASH, decision.fingerprint)
                        }
                    }
                    is ReleaseDecision.UpToDate -> decision.baseline?.let { seen ->
                        prefs.setString(PrefKeys.UPDATE_SEEN_HASH, seen.fingerprint)
                        prefs.setString(PrefKeys.UPDATE_SEEN_VERSION, seen.version)
                    }
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

    val title = when (shown) {
        is NoticePhase.Offer ->
            if (update.sameVersion) "A new build of Pear ${update.version} is available" else "Pear ${update.version} is available"
        is NoticePhase.Downloading -> "Pear ${update.version}"
        is NoticePhase.Opening -> "Opening the installer…"
        is NoticePhase.Failed -> "The update did not finish"
    }
    val detail = when (shown) {
        is NoticePhase.Offer -> "You have ${PearBuild.VERSION}."
        is NoticePhase.Downloading -> downloadText(progress)
        is NoticePhase.Opening -> "The installer opens in its own window."
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
                is NoticePhase.Offer -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearCapsuleButton(label = "Update", style = PearButtonStyle.Accent, onClick = { startDownload(update) })
                    PearCapsuleButton(
                        label = "Later",
                        onClick = {
                            dismissed = true
                            phase = null
                        },
                    )
                }
                is NoticePhase.Downloading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearCapsuleButton(label = "Cancel", onClick = { cancelDownload(update) })
                }
                is NoticePhase.Opening -> Unit
                is NoticePhase.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearCapsuleButton(
                        label = "Try again",
                        style = PearButtonStyle.Accent,
                        onClick = {
                            val saved = shown.savedAt
                            if (saved != null) openInstallerFile(update, saved) else startDownload(update)
                        },
                    )
                    PearCapsuleButton(
                        label = "Later",
                        onClick = {
                            dismissed = true
                            phase = null
                        },
                    )
                }
            }
            if (shown !is NoticePhase.Downloading && shown !is NoticePhase.Opening) {
                PearText(
                    "Release page",
                    style = PearTheme.type.footnote,
                    color = c.accent,
                    modifier = Modifier.clickable(role = Role.Button) {
                        openUrl(update.releaseUrl.ifEmpty { update.asset.url })
                    },
                )
            }
        }
    }
}
