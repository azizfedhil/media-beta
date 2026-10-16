package com.mediahub.pear.player.mpv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.net.FetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface GateState {
    /** Looking at the file on disk (hashing it), before anything is shown. */
    data object Checking : GateState

    /** Fetching libmpv. [total] is -1 until the server says how big the file is. */
    data class Downloading(val done: Long, val total: Long) : GateState

    /** Every source failed. The user can retry, or go on without video. */
    data class Failed(val reasons: List<String>) : GateState

    /** Nothing to do, or it is done: show the app. */
    data object Ready : GateState
}

/**
 * Shows "Setting up Pear…" while libmpv is missing or does not match its pin, fetches it, and then shows [content].
 *
 * It does nothing where it has nothing to fetch: anywhere but Windows, in a build without a pin, and when `PEAR_LIBMPV`
 * points at a copy. The app's own colours are not used here, because the app's theme is set up inside [content].
 */
@Composable
fun LibmpvSetupGate(content: @Composable () -> Unit) {
    val target = remember { LibmpvSetup.target() }
    var state by remember { mutableStateOf<GateState>(if (target == null) GateState.Ready else GateState.Checking) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        val file = target ?: return@LaunchedEffect
        state = GateState.Checking
        val missing = withContext(Dispatchers.IO) { LibmpvSetup.needsFetch() }
        if (!missing) {
            state = GateState.Ready
            return@LaunchedEffect
        }
        state = GateState.Downloading(0L, -1L)
        var lastShown = 0L
        val result = withContext(Dispatchers.IO) {
            LibmpvSetup.fetch(file) { done, total ->
                // About one update per megabyte: a 64 KB redraw for every chunk would cost more than the download.
                if (done - lastShown >= 1_000_000L || done == total) {
                    lastShown = done
                    state = GateState.Downloading(done, total)
                }
            }
        }
        state = when (result) {
            FetchResult.Ready -> GateState.Ready
            is FetchResult.Failed -> GateState.Failed(result.reasons)
        }
    }

    when (val current = state) {
        GateState.Ready -> content()
        GateState.Checking -> SetupBackground {}
        is GateState.Downloading -> DownloadScreen(current)
        is GateState.Failed -> FailedScreen(
            reasons = current.reasons,
            onRetry = { attempt += 1 },
            onContinue = { state = GateState.Ready },
        )
    }
}

private val Background = Color(0xFF09090B)
private val Muted = Color(0xFF9A9AA0)

@Composable
private fun SetupBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun DownloadScreen(state: GateState.Downloading) {
    SetupBackground {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                "Setting up Pear…",
                style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(10.dp))
            val detail = if (state.total > 0) {
                "Downloading libmpv, the video engine: ${state.done / 1_000_000} of ${state.total / 1_000_000} MB"
            } else {
                "Downloading libmpv, the video engine. This happens once."
            }
            BasicText(detail, style = TextStyle(color = Muted, fontSize = 13.sp))
            Spacer(Modifier.height(20.dp))
            val fraction = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
            Box(Modifier.width(320.dp).height(4.dp).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Color.White))
            }
        }
    }
}

@Composable
private fun FailedScreen(reasons: List<String>, onRetry: () -> Unit, onContinue: () -> Unit) {
    SetupBackground {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            BasicText(
                "Pear couldn't set up its video engine.",
                style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(10.dp))
            BasicText(
                "Video won't play until libmpv is in place. Try again, or go on without video for now.",
                style = TextStyle(color = Muted, fontSize = 13.sp),
            )
            Spacer(Modifier.height(12.dp))
            reasons.forEach { reason ->
                BasicText(reason, style = TextStyle(color = Muted, fontSize = 12.sp))
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Try again", onRetry)
                PillButton("Continue without video", onContinue)
            }
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        BasicText(label, style = TextStyle(color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}
