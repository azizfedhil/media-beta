package com.mediahub.pear.player.mpv

import com.mediahub.pear.net.FetchResult
import com.mediahub.pear.net.fetchVerified
import com.mediahub.pear.net.isSha256Hex
import com.mediahub.pear.net.sha256Hex
import java.io.File

/**
 * Windows only: the libmpv the installer puts in `%LOCALAPPDATA%\Pear\native`, and that the app fetches again when the file
 * is missing or does not match the pin (a failed or interrupted install). Other systems use their own libmpv, and nothing
 * here runs for them.
 */
object LibmpvSetup {
    /**
     * SHA-256 of the 64-bit `libmpv-2.dll` this build fetches, from `python tools/inspect_binary.py <file>`. It must equal
     * `LibmpvSha256` in `desktopApp/installer/libmpv.pin.iss`; a test fails if they differ. Until this is a real hash,
     * nothing is downloaded, and the player reports the missing libmpv as it does on a source run.
     */
    const val SHA256: String = "TBD"

    /** The Google Drive copy, the first choice. Drive can rate-limit or change its page, so there is a second source. */
    const val DRIVE_URL: String =
        "https://drive.usercontent.google.com/download?id=1zF1TbPHoSBVTRzjPVX7ktmwWb6rAUVM4&export=download&confirm=t"

    /** A GitHub Release asset with the same file and hash. Empty until the asset exists. */
    const val MIRROR_URL: String = ""

    const val FILE_NAME: String = "libmpv-2.dll"

    val isPinned: Boolean get() = isSha256Hex(SHA256)

    /** The download addresses, first choice first. */
    val sources: List<String> get() = listOfNotNull(DRIVE_URL, MIRROR_URL.takeIf { it.startsWith("https://") })

    /** Where the file belongs: `%LOCALAPPDATA%\Pear\native\libmpv-2.dll`. Null off Windows. */
    fun target(env: (String) -> String? = { System.getenv(it) }): File? {
        if (MpvNativeLoader.Platform.current != MpvNativeLoader.Platform.Windows) return null
        return MpvNativeLoader.windowsNativeFolder(env)?.let { File(it, FILE_NAME) }
    }

    /** True when [file] exists and matches the pin. Reads the whole file, so call this off the UI thread. */
    fun isInstalled(file: File): Boolean =
        isPinned && file.isFile && sha256Hex(file).equals(SHA256, ignoreCase = true)

    /**
     * Whether this run should fetch: Windows, a real pin, no `PEAR_LIBMPV` override, and the file is not already right.
     * Reads the file to check it, so call this off the UI thread.
     */
    fun needsFetch(env: (String) -> String? = { System.getenv(it) }): Boolean {
        if (!isPinned) return false
        if (!env("PEAR_LIBMPV").isNullOrBlank()) return false
        val file = target(env) ?: return false
        return !isInstalled(file)
    }

    /** Fetches [file]. Blocking: call it off the UI thread. */
    fun fetch(file: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): FetchResult =
        fetchVerified(sources, SHA256, file, onProgress)
}
