package com.mediahub.pear.downloads

import com.mediahub.pear.data.AndroidDirs

actual fun platformDownloadFiles(): DownloadFiles = FolderDownloadFiles(AndroidDirs.downloads())

actual fun platformDownloadTransport(): DownloadTransport = HttpDownloadTransport()
