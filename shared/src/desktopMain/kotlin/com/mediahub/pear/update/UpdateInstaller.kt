package com.mediahub.pear.update

import com.mediahub.pear.net.downloadTo
import com.mediahub.pear.net.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

/** Where downloaded installers wait: a folder of their own in the temp dir, cleared before each download. */
private val updateFolder: File
    get() = File(System.getProperty("java.io.tmpdir"), "pear-update")

/**
 * Downloads [asset] into the update folder and returns the file. [onProgress] gets (bytes done, total or -1 if unknown).
 * When the release gave a SHA-256, the file must have it: otherwise it is deleted and this throws [IOException]. Cancelling
 * the caller stops the download between reads, and the partial file is deleted.
 */
suspend fun downloadUpdate(
    asset: ReleaseAsset,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
): File = withContext(Dispatchers.IO) {
    val folder = updateFolder
    folder.mkdirs()
    // A file still in use (an installer that is running, say) cannot be deleted, and stays: that is fine.
    folder.listFiles()?.forEach { it.delete() }

    val name = File(asset.name).name.ifEmpty { "PearUpdate" }
    val target = File(folder, name)
    // A staging file of its own: a cancelled download must never delete a newer one that replaced it.
    val staging = File(folder, "$name.${System.nanoTime()}.download")
    val job: Job? = coroutineContext[Job]
    try {
        downloadTo(asset.url, staging, onProgress = { done, total ->
            // downloadTo reads in chunks and calls this for each one, so a cancel stops it between reads.
            if (job?.isActive == false) throw CancellationException("the download was cancelled")
            onProgress(done, total)
        })
        asset.sha256?.let { expected ->
            val actual = sha256Hex(staging)
            if (actual != expected) {
                throw IOException("the file is not the one the release has (its SHA-256 is $actual)")
            }
        }
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        target
    } finally {
        staging.delete()
    }
}

/**
 * Runs the installer [file]. On Windows it starts the setup program: it is set up to close Pear and start it again when it
 * is done, so this does not exit the app. On Linux the .rpm goes to the desktop's own opener. A failure is a failed
 * [Result]; the file stays where it is, so the caller can say where it is saved.
 */
fun openInstaller(file: File): Result<Unit> = runCatching {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if ("win" in os) {
        ProcessBuilder(file.absolutePath).start()
    } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(file)
    } else {
        ProcessBuilder("xdg-open", file.absolutePath).start()
    }
    Unit
}
