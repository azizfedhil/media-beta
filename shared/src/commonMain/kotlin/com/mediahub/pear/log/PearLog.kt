package com.mediahub.pear.log

/**
 * The app's log, for code in commonMain. Lines go to a [LogSink]. The desktop and Android builds install the file logger
 * at startup (`PearLogFile` in jvmCommonMain). Until a sink is set, lines are dropped, so calling this is always safe and
 * never blocks the UI.
 */
object PearLog {
    var sink: LogSink? = null

    fun i(tag: String, msg: String) {
        sink?.write('I', tag, msg, null)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        sink?.write('W', tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        sink?.write('E', tag, msg, t)
    }

    /** A small fact for the crash briefing (the current route, the title playing, the window mode). `null` removes it. */
    fun context(key: String, value: String?) {
        sink?.context(key, value)
    }
}

interface LogSink {
    fun write(level: Char, tag: String, msg: String, t: Throwable?)

    fun context(key: String, value: String?)
}

/**
 * A URL that is safe to log: the host and the path up to the first "?", with any long run of token-like characters in the
 * path replaced by "…". Add-on manifest URLs often carry a debrid key in the path, so a raw URL is never logged.
 */
fun safeLogUrl(url: String): String {
    val noScheme = url.substringAfter("://", url)
    val noQuery = noScheme.substringBefore('?').substringBefore('#')
    val host = noQuery.substringBefore('/')
    val path = noQuery.substring(host.length)
    return host + TOKEN_RUN.replace(path, "…")
}

private val TOKEN_RUN = Regex("[A-Za-z0-9_-]{25,}")
