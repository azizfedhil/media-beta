package com.mediahub.pear.player.mpv

import com.mediahub.pear.net.FetchResult
import com.mediahub.pear.net.fetchVerified
import com.mediahub.pear.net.isSha256Hex
import com.mediahub.pear.net.sha256Hex
import java.io.File

/**
 * Windows only: the libmpv the installer puts in `%LOCALAPPDATA%\Pear\native`, which the app fetches again when the file is
 * missing or does not match. Other systems use their own libmpv, and nothing here runs for them.
 *
 * The link and the hash are not in the source. The build downloads the link and writes [LibmpvBuild] (see
 * shared/build.gradle.kts). A build without a hash (offline, or not Windows) never downloads anything.
 */
object LibmpvSetup {
    /** SHA-256 of the libmpv-2.dll this build fetched and checked. Empty when the build did not fetch it. */
    val SHA256: String get() = LibmpvBuild.SHA256

    /** The download link (Google Drive): the first choice. */
    val DRIVE_URL: String get() = LibmpvBuild.DRIVE_URL

    /** A second copy, tried when the first fails. Empty until one is configured in gradle.properties. */
    val MIRROR_URL: String get() = LibmpvBuild.MIRROR_URL

    const val FILE_NAME: String = "libmpv-2.dll"

    val isPinned: Boolean get() = isSha256Hex(SHA256)

    /** The download addresses, first choice first. Only https addresses are used. */
    val sources: List<String>
        get() = listOfNotNull(
            DRIVE_URL.takeIf { it.startsWith("https://") },
            MIRROR_URL.takeIf { it.startsWith("https://") },
        )

    /** Where the file belongs: `%LOCALAPPDATA%\Pear\native\libmpv-2.dll`. Null off Windows. */
    fun target(env: (String) -> String? = { System.getenv(it) }): File? {
        if (MpvNativeLoader.Platform.current != MpvNativeLoader.Platform.Windows) return null
        return MpvNativeLoader.windowsNativeFolder(env)?.let { File(it, FILE_NAME) }
    }

    /** True when [file] exists and matches the hash. Reads the whole file, so call this off the UI thread. */
    fun isInstalled(file: File): Boolean =
        isPinned && file.isFile && sha256Hex(file).equals(SHA256, ignoreCase = true)

    /**
     * Whether this run should fetch: Windows, a hash in this build, no `PEAR_LIBMPV` override, and the file is not already
     * right. Reads the file to check it, so call this off the UI thread.
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
