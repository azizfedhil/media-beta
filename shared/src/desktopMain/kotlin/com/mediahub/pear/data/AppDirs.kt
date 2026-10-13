package com.mediahub.pear.data

import java.io.File

/** Where the app keeps its files on the desktop. */
class AppDirs(
    /** Settings and secrets. */
    val config: File,
    /** Disk caches (Batch 2.2). Safe to delete. */
    val cache: File,
) {
    companion object {
        /**
         * Windows: %APPDATA%\Pear and %LOCALAPPDATA%\Pear\Cache.
         * Linux: $XDG_CONFIG_HOME/pear (default ~/.config/pear) and $XDG_CACHE_HOME/pear.
         * macOS (for development): ~/Library/Application Support/Pear and ~/Library/Caches/Pear.
         * [env] and [home] are parameters so tests can pass their own.
         */
        fun resolve(osName: String, env: (String) -> String?, home: String): AppDirs {
            val os = osName.lowercase()
            fun nonBlank(name: String) = env(name)?.takeIf { it.isNotBlank() }
            return when {
                "win" in os && "darwin" !in os -> {
                    val roaming = nonBlank("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
                    val local = nonBlank("LOCALAPPDATA")?.let(::File) ?: File(home, "AppData/Local")
                    AppDirs(File(roaming, "Pear"), File(local, "Pear/Cache"))
                }
                "mac" in os || "darwin" in os ->
                    AppDirs(File(home, "Library/Application Support/Pear"), File(home, "Library/Caches/Pear"))
                else -> {
                    val config = nonBlank("XDG_CONFIG_HOME")?.let(::File) ?: File(home, ".config")
                    val cache = nonBlank("XDG_CACHE_HOME")?.let(::File) ?: File(home, ".cache")
                    AppDirs(File(config, "pear"), File(cache, "pear"))
                }
            }
        }

        fun detect(): AppDirs = resolve(System.getProperty("os.name") ?: "", System::getenv, System.getProperty("user.home") ?: ".")
    }
}
