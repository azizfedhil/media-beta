package com.mediahub.pear.downloads

import com.mediahub.pear.data.AppDirs
import java.io.File

// The transport and the folder store are shared with Android (jvmCommonMain/downloads/JvmDownloads.kt).
actual fun platformDownloadFiles(): DownloadFiles = FolderDownloadFiles(File(AppDirs.detect().config, "downloads"))

actual fun platformDownloadTransport(): DownloadTransport = HttpDownloadTransport()
