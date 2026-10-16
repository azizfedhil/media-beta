package com.mediahub.pear.p2p

import java.io.File

/**
 * Finds the p2p_core library (built by `gradle buildP2pCore`) and points UniFFI's loader at it.
 *
 * The generated Kotlin bindings load the library by name, unless `uniffi.component.p2p_core.libraryOverride` names
 * the file. That property is set here, just before the first engine call, so the bindings never search the system.
 *
 * Where it is looked for, in order: the `P2P_LIBRARY` environment variable (a full path), the app's bundled resources
 * (an installed app, `compose.application.resources.dir`), and the packaging folder of a source run.
 */
internal object P2PLibrary {
    const val OVERRIDE_PROPERTY = "uniffi.component.p2p_core.libraryOverride"

    /** The file name per operating system, and the folder `packaging/resources` uses for it. */
    private val os: String get() = System.getProperty("os.name").orEmpty().lowercase()

    val fileName: String
        get() = when {
            "win" in os -> "p2p_core.dll"
            "mac" in os || "darwin" in os -> "libp2p_core.dylib"
            else -> "libp2p_core.so"
        }

    private val folder: String
        get() = when {
            "win" in os -> "windows-x64"
            "linux" in os -> "linux-x64"
            else -> "other"
        }

    /** The library file, or null. Keeps the places it looked, for the message. */
    fun locate(searched: MutableList<String>? = null): File? {
        System.getenv("P2P_LIBRARY")?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            searched?.add(path)
            if (f.isFile) return f
        }
        for (dir in searchDirectories()) {
            searched?.add(dir.path)
            val f = File(dir, fileName)
            if (f.isFile) return f
        }
        return null
    }

    /** Where the library is looked for, as the message says it. */
    fun describeMissing(): String {
        val searched = mutableListOf<String>()
        locate(searched)
        return "The P2P engine library ($fileName) is missing. Build it with `gradle :shared:buildP2pCore`, or " +
            "reinstall Pear. Looked in: ${searched.joinToString("; ")}"
    }

    /** Points UniFFI at [file]. Call before the first engine call. */
    fun point(file: File) {
        System.setProperty(OVERRIDE_PROPERTY, file.absolutePath)
    }

    private fun searchDirectories(): List<File> {
        val dirs = ArrayList<File>()
        System.getProperty("compose.application.resources.dir")?.let { dirs.add(File(it)) }
        val user = File(System.getProperty("user.dir").orEmpty().ifEmpty { "." })
        // Source runs: the copy the build puts in the packaging folder, from the project root or from desktopApp/.
        for (base in listOfNotNull(user, user.parentFile)) {
            dirs.add(File(base, "desktopApp/packaging/resources/$folder"))
            dirs.add(File(base, "packaging/resources/$folder"))
        }
        return dirs.distinctBy { it.absolutePath }
    }
}
