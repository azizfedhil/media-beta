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
    val name = File(asset.name).name.ifEmpty { "PearUpdate" }
    val target = File(folder, name)
    // An installer from an earlier run (the person chose Later) is used again when it is the one this release has.
    if (target.isFile && asset.size > 0 && target.length() == asset.size &&
        (asset.sha256 == null || sha256Hex(target) == asset.sha256)
    ) {
        onProgress(asset.size, asset.size)
        return@withContext target
    }
    // A file still in use (an installer that is running, say) cannot be deleted, and stays: that is fine.
    folder.listFiles()?.forEach { it.delete() }
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

/** True where running the installer replaces this app, so the app quits once the installer has started: Windows. */
fun installerReplacesApp(): Boolean = "win" in System.getProperty("os.name").orEmpty().lowercase()

/**
 * Runs the installer [file]. On Windows it starts `PearSetup.exe` silently: no wizard pages, only a small progress window
 * (and the installer's own error box if something fails; do NOT pass /SUPPRESSMSGBOXES, because its libmpv step uses a
 * Retry box). The installer closes Pear if it is still running and starts it again when done (see pear.iss). The caller
 * quits the app after this returns. On Linux the .rpm goes to the desktop's own opener. A failure is a failed [Result];
 * the file stays where it is.
 */
fun openInstaller(file: File): Result<Unit> = runCatching {
    if (installerReplacesApp()) {
        ProcessBuilder(file.absolutePath, "/SILENT", "/CLOSEAPPLICATIONS", "/NORESTARTAPPLICATIONS", "/NOCANCEL").start()
    } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(file)
    } else {
        ProcessBuilder("xdg-open", file.absolutePath).start()
    }
    Unit
}
