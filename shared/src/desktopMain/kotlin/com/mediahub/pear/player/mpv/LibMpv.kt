package com.mediahub.pear.player.mpv

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

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

object MpvNativeLoader {
    private val CANDIDATES = listOf("mpv-2", "libmpv-2", "mpv-1", "libmpv-1", "mpv", "libmpv")

    fun load(): LibMpv? {
        for (name in CANDIDATES) {
            try {
                return Native.load(name, LibMpv::class.java)
            } catch (_: Throwable) {}
        }
        return null
    }
}