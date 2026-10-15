package com.mediahub.pear.player.mpv

/**
 * What the viewer reads when a source fails on Android. The JNI wrapper reports END_FILE without the error code, so
 * this reads the last error line mpv logged for the file. The match strings are mpv's wording as expected; they are
 * checked on a device (docs/android/PROGRESS.md).
 */
internal object MpvErrorText {
    private val httpStatus = Regex("""HTTP error (\d{3})""")

    private val networkWords = listOf(
        "connection refused", "timed out", "failed to resolve", "network is unreachable",
        "connection reset", "name or service not known",
    )
    private val formatWords = listOf(
        "failed to recognize file format", "could not open codec", "failed to initialize a decoder",
        "no video or audio streams", "unsupported",
    )

    fun describe(lastError: String?): String {
        val text = lastError.orEmpty()
        httpStatus.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
            return when (code) {
                401, 403 -> "The source refused access (HTTP $code)."
                404 -> "The file was not found (HTTP 404)."
                in 500..599 -> "The source's server had a problem (HTTP $code)."
                else -> "The source answered with HTTP $code."
            }
        }
        val lower = text.lowercase()
        return when {
            networkWords.any { it in lower } -> "Couldn't reach the source."
            formatWords.any { it in lower } -> "This device can't play this format."
            else -> "Could not play this source."
        }
    }
}
