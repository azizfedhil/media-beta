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
 * 4. `native/<platform>` in the project, from the project root or from `desktopApp/` (see `desktopApp/native/README.md`).
 * 5. The system's own search: PATH on Windows, the dynamic loader's search path on Linux.
 *
 * The last error from the first attempt that failed is kept, because it's the useful one: a 32-bit DLL, or a
 * dependency missing next to it, shows up there and not as "not found".
 */
object MpvNativeLoader {
    private val SYSTEM_NAMES = listOf("mpv-2", "libmpv-2", "mpv-1", "libmpv-1", "mpv", "libmpv")

    /** The outcome of a load: the library, or the folders searched and the last error when it isn't there. */
    class Result(val lib: LibMpv?, val searched: List<String>, val lastError: String?) {
        /** The message the player shows when [lib] is null. */
        fun describeFailure(): String = buildString {
            append("libmpv not found. ").append(Platform.current.hint)
            if (searched.isNotEmpty()) append("\nLooked in: ").append(searched.joinToString("; "))
            if (lastError != null) append("\nLast error: ").append(lastError)
        }
    }

    /** Operating system, with the library file names and the folder name `native/` uses for it. */
    internal enum class Platform(val fileNames: List<String>, val folder: String, val hint: String) {
        Windows(
            listOf("libmpv-2.dll", "mpv-2.dll", "libmpv.dll"),
            "windows-x64",
            "Copy libmpv-2.dll (a 64-bit build, such as the mpv-dev package) into desktopApp/native/windows-x64/, " +
                "or set PEAR_LIBMPV to its full path.",
        ),
        Linux(
            listOf("libmpv.so.2", "libmpv.so.1", "libmpv.so"),
            "linux-x64",
            "Install libmpv (for example: sudo apt install libmpv2), or copy libmpv.so.2 into " +
                "desktopApp/native/linux-x64/.",
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
        var lastError: String? = null

        fun tryLoad(target: String): LibMpv? = try {
            Native.load(target, LibMpv::class.java)
        } catch (e: Throwable) {
            lastError = e.message?.lineSequence()?.firstOrNull()?.take(300) ?: e.javaClass.simpleName
            null
        }

        System.getenv("PEAR_LIBMPV")?.takeIf { it.isNotBlank() }?.let { path ->
            tryLoad(path)?.let { return Result(it, searched, null) }
        }
        for (dir in dirs) {
            for (name in platform.fileNames) {
                val file = File(dir, name)
                if (!file.isFile) continue
                tryLoad(file.absolutePath)?.let { return Result(it, searched, null) }
            }
        }
        for (name in SYSTEM_NAMES) {
            tryLoad(name)?.let { return Result(it, searched, null) }
        }
        return Result(null, searched, lastError)
    }

    private fun searchDirectories(platform: Platform): List<File> {
        val dirs = ArrayList<File>()
        System.getenv("PEAR_LIBMPV_DIR")?.takeIf { it.isNotBlank() }?.let { dirs.add(File(it)) }
        System.getProperty("compose.application.resources.dir")?.let { dirs.add(File(it)) }
        val user = File(System.getProperty("user.dir").orEmpty().ifEmpty { "." })
        for (base in listOfNotNull(user, user.parentFile)) {
            dirs.add(File(base, "native/${platform.folder}"))
            dirs.add(File(base, "desktopApp/native/${platform.folder}"))
        }
        return dirs.distinctBy { it.absolutePath }
    }
}
