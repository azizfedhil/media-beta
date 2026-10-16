package com.mediahub.pear.downloads

/** Where the platform keeps downloaded files (desktop: a folder under the app's config folder). */
expect fun platformDownloadFiles(): DownloadFiles

/** How the platform fetches a file (desktop: the JDK's HTTP client, streamed to disk). */
expect fun platformDownloadTransport(): DownloadTransport
