package com.mediahub.pear.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.p2p.P2PEngine
import com.mediahub.pear.p2p.P2PFailure
import com.mediahub.pear.p2p.P2PSource
import com.mediahub.pear.p2p.P2PStatus
import com.mediahub.pear.p2p.p2pPlatform
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Big Buck Bunny (Blender Foundation, CC-BY 3.0): a public torrent with a healthy swarm, for checking the engine. */
private const val TEST_INFO_HASH = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
private val TEST_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
)

/**
 * Settings -> Developer -> P2P (Batch 4.5a). Starts the engine on the test torrent, prints its status, stops it, and
 * counts the cache folders a stop leaves behind (there should be none). Leaving the screen stops the engine.
 */
@Composable
internal fun P2PCheckScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val engine = remember { P2PEngine(p2pPlatform()) }
    val log = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }

    // No socket outlives the check: the screen going away is a killswitch.
    DisposableEffect(engine) { onDispose { engine.requestStop() } }

    fun act(name: String, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            log.add("> $name")
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: P2PFailure) {
                log.add("✗ ${e.message}")
            } catch (e: Exception) {
                log.add("✗ ${e.message ?: e::class.simpleName ?: "error"}")
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 92.dp, bottom = 32.dp).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            PearText("P2P engine check", style = PearTheme.type.headline)
            PearText(
                "The engine (p2p-core, Rust) loads on the first Start. Stop ends the stream and closes the engine. " +
                    "Leftover cache folders should read 0 after a stop.",
                style = PearTheme.type.footnote,
                color = PearTheme.colors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(onClick = { act("Start engine") { startEngine(engine, log) } }, label = "Start engine", enabled = !busy)
                GlassButton(onClick = { act("Status") { log.add(render(engine.status(), engine.leftoverCacheEntries())) } }, label = "Status", enabled = !busy)
                GlassButton(
                    onClick = {
                        act("Stop") {
                            engine.stop()
                            log.add("Stopped. Leftover cache folders: ${engine.leftoverCacheEntries()}")
                        }
                    },
                    label = "Stop",
                    enabled = !busy,
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            PearText("Log", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
            if (log.isEmpty()) {
                PearText("Nothing yet.", style = PearTheme.type.footnote, color = PearTheme.colors.textTertiary)
            }
            log.forEach { line ->
                PearText(line, style = PearTheme.type.footnote, color = PearTheme.colors.textPrimary)
            }
        }
    }

    GlassTopBar(title = "P2P", modifier = Modifier.padding(16.dp), onBack = onBack)
}

/** Starts the engine on the test torrent. Prints the file's name and size, never the stream URL: it carries a token. */
private suspend fun startEngine(engine: P2PEngine, log: MutableList<String>) {
    val stream = engine.open(P2PSource(TEST_INFO_HASH, TEST_TRACKERS))
    log.add("✓ Stream ready: ${stream.fileName}, ${stream.length / 1_048_576} MB (file ${stream.fileIndex}). URL not shown.")
}

private fun render(status: P2PStatus, leftover: Int): String = buildString {
    append("Engine loaded: ").append(if (status.engineLoaded) "yes" else "no")
    val s = status.stats
    if (s == null) {
        append("\nNo stream open.")
    } else {
        append("\nDownloaded ${s.downloadedBytes / 1_048_576} of ${s.totalBytes / 1_048_576} MB, ")
        append("${s.peersLive} peers, ${s.downloadBps / 1024} KB/s, uploaded ${s.uploadedBytes / 1_048_576} MB.")
    }
    append("\nLeftover cache folders: ").append(leftover)
}
