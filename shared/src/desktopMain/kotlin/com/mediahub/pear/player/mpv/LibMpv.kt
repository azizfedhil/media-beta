package com.mediahub.pear.player.mpv

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.io.File

object MpvFormat {
    const val NONE = 0
    const val STRING = 1
    const val OSD_STRING = 2
    const val FLAG = 3
    const val INT64 = 4
    const val DOUBLE = 5
    const val NODE = 6
}

object MpvEventId {
    const val NONE = 0
    const val SHUTDOWN = 1
    const val LOG_MESSAGE = 2
    const val GET_PROPERTY_REPLY = 3
    const val SET_PROPERTY_REPLY = 4
    const val COMMAND_REPLY = 5
    const val START_FILE = 6
    const val END_FILE = 7
    const val FILE_LOADED = 8
    const val PROPERTY_CHANGE = 22
}

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
open class MpvEvent : Structure {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
}

@Structure.FieldOrder("name", "format", "data")
open class MpvEventProperty : Structure {
    @JvmField var name: String? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
}

/** Called by mpv from any thread when a new frame is ready. Do nothing here except signal another thread. */
interface MpvRenderUpdateCallback : Callback {
    fun invoke(ctx: Pointer?)
}

object MpvRenderParam {
    const val API_TYPE = 1
    const val SW_SIZE = 17
    const val SW_FORMAT = 18
    const val SW_STRIDE = 19
    const val SW_POINTER = 20
    const val UPDATE_FRAME = 1L
}

interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_destroy(ctx: Pointer)
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer?)
    fun mpv_observe_property(ctx: Pointer, reply_userdata: Long, name: String, format: Int): Int
    fun mpv_unobserve_property(ctx: Pointer, registered_reply_userdata: Long): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
    fun mpv_error_string(error: Int): String?

    // Render API (software renderer). `params` is an array of { int type; void *data } ending with type 0.
    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: MpvRenderUpdateCallback?, callbackCtx: Pointer?)
    fun mpv_render_context_free(ctx: Pointer)
}

/**
 * Finds libmpv and says where it looked when it can't. Searched in this order:
 *
 * 1. `PEAR_LIBMPV`: the full path of the library file.
 * 2. `PEAR_LIBMPV_DIR`: a folder that holds it.
 * 3. The folder of an installed app (`compose.application.resources.dir`).
 * 4. Windows only: `%LOCALAPPDATA%\Pear\native`, where the installer puts libmpv. It is outside the app folder, so an
 *    update leaves it in place.
 * 5. `native/<platform>` in the project, from the project root or from `desktopApp/` (see `desktopApp/native/README.md`).
 * 6. The system's own search: PATH on Windows, the dynamic loader's search path on Linux.
 *
 * The last error from the first attempt that failed is kept, because it's the useful one: a 32-bit DLL, or a
 * dependency missing next to it, shows up there and not as "not found".
 */
object MpvNativeLoader {
    private val SYSTEM_NAMES = listOf("mpv-2", "libmpv-2", "mpv-1", "libmpv-1", "mpv", "libmpv")

    /**
     * The outcome of a load: the library, or why there isn't one. [fileError] is set only when a file with the right
     * name was found but would not load (a 32-bit build, or a missing dependency next to it). Without it the message
     * says which file names were looked for, which is what the user has to act on.
     */
    class Result(
        val lib: LibMpv?,
        val searched: List<String>,
        val lookedFor: List<String>,
        val fileError: String?,
    ) {
        /** The message the player shows when [lib] is null. */
        fun describeFailure(): String = buildString {
            append("libmpv not found. ").append(Platform.current.hint)
            append("\nLooking for: ").append(lookedFor.joinToString(" or "))
            append("\nIn: ").append(searched.joinToString("; "))
            if (fileError != null) append("\nFound a file, but it would not load: ").append(fileError)
        }
    }

    /** Operating system, with the library file names and the folder name `native/` uses for it. */
    internal enum class Platform(val fileNames: List<String>, val folder: String, val hint: String) {
        Windows(
            listOf("libmpv-2.dll", "mpv-2.dll", "libmpv.dll"),
            "windows-x64",
            "Pear's libmpv is missing. Run the Pear installer again to restore it (it is kept in " +
                "%LOCALAPPDATA%\\Pear\\native). Building from source: see desktopApp/packaging/resources/README.md.",
        ),
        Linux(
            listOf("libmpv.so.2", "libmpv.so.1", "libmpv.so"),
            "linux-x64",
            "Install libmpv: Fedora `sudo dnf install mpv-libs` (from RPM Fusion), Ubuntu/Debian `sudo apt install libmpv2`.",
        ),
        Other(
            listOf("libmpv.2.dylib", "libmpv.dylib", "libmpv.so.2", "libmpv.so"),
            "other",
            "Set PEAR_LIBMPV to the full path of the libmpv library.",
        );

        companion object {
            val current: Platform
                get() {
                    val os = System.getProperty("os.name").orEmpty().lowercase()
                    return when {
                        "win" in os -> Windows
                        "linux" in os -> Linux
                        else -> Other
                    }
                }
        }
    }

    fun load(): LibMpv? = loadWithReport().lib

    fun loadWithReport(): Result {
        val platform = Platform.current
        val dirs = searchDirectories(platform)
        val searched = dirs.map { it.path }
        val lookedFor = platform.fileNames
        var fileError: String? = null

        // A file that exists but won't load is the one error worth reporting. A system name that isn't found is not.
        fun tryFile(path: String): LibMpv? = try {
            Native.load(path, LibMpv::class.java)
        } catch (e: Throwable) {
            if (fileError == null) fileError = e.message?.lineSequence()?.firstOrNull()?.take(300) ?: e.javaClass.simpleName
            null
        }

        System.getenv("PEAR_LIBMPV")?.takeIf { it.isNotBlank() }?.let { path ->
            tryFile(path)?.let { return Result(it, searched, lookedFor, null) }
        }
        for (dir in dirs) {
            for (name in platform.fileNames) {
                val file = File(dir, name)
                if (!file.isFile) continue
                tryFile(file.absolutePath)?.let { return Result(it, searched, lookedFor, null) }
            }
        }
        for (name in systemNames(platform)) {
            try {
                return Result(Native.load(name, LibMpv::class.java), searched, lookedFor, null)
            } catch (_: Throwable) {
                // Not on the system search path either: keep looking at the rest.
            }
        }
        return Result(null, searched, lookedFor, fileError)
    }

    private fun searchDirectories(platform: Platform): List<File> =
        searchDirectories(platform, env = { System.getenv(it) }, property = { System.getProperty(it) })

    /**
     * The folders to search, in order. It depends only on its inputs, not on the process, so the order can be tested.
     */
    internal fun searchDirectories(
        platform: Platform,
        env: (String) -> String?,
        property: (String) -> String?,
    ): List<File> {
        val dirs = ArrayList<File>()
        env("PEAR_LIBMPV_DIR")?.takeIf { it.isNotBlank() }?.let { dirs.add(File(it)) }
        property("compose.application.resources.dir")?.let { dirs.add(File(it)) }
        if (platform == Platform.Windows) windowsNativeFolder(env)?.let { dirs.add(it) }
        val user = File(property("user.dir").orEmpty().ifEmpty { "." })
        // Source runs: the copies a developer has put in the packaging folder, then the older native/ folders.
        for (base in listOfNotNull(user, user.parentFile)) {
            dirs.add(File(base, "desktopApp/packaging/resources/${platform.folder}"))
            dirs.add(File(base, "packaging/resources/${platform.folder}"))
            dirs.add(File(base, "native/${platform.folder}"))
            dirs.add(File(base, "desktopApp/native/${platform.folder}"))
        }
        return dirs.distinctBy { it.absolutePath }
    }

    /** `%LOCALAPPDATA%\Pear\native`, the folder that holds libmpv on Windows. Null when the variable is not set. */
    internal fun windowsNativeFolder(env: (String) -> String?): File? {
        val local = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: return null
        return File(File(local, "Pear"), "native")
    }

    /**
     * The names to ask the system's loader for, in order. A distribution's runtime package ships only the versioned
     * soname (`libmpv.so.2`: Ubuntu's libmpv2, Fedora's mpv-libs). The unversioned `libmpv.so` comes with the -dev package.
     */
    internal fun systemNames(platform: Platform): List<String> =
        if (platform == Platform.Linux) listOf("libmpv.so.2", "libmpv.so.1") + SYSTEM_NAMES else SYSTEM_NAMES
}
