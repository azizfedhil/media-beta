package com.mediahub.pear.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A system keyring: stores a value under a name, returns false when it can't. */
internal interface Keyring {
    val name: String
    fun store(key: String, value: String): Boolean
    fun lookup(key: String): String?
    fun clear(key: String)
}

/** Encrypts bytes so that only this user on this machine can decrypt them. */
internal interface Protector {
    val name: String
    fun protect(data: ByteArray): ByteArray?
    fun unprotect(data: ByteArray): ByteArray?
}

/**
 * Secrets on the desktop (spec: `Keychain.swift`). In order:
 * 1. A system keyring (Linux: the Secret Service through the `secret-tool` command, which GNOME Keyring and KWallet provide).
 * 2. Windows DPAPI: the value is encrypted for the signed-in user and kept in `secrets.json`.
 * 3. The same file in plain text, with owner-only permissions where the system supports them.
 * Step 3 is the fallback the iOS app also has: a token is kept rather than lost on every restart.
 * [set] returns true for 1 and 2.
 */
class DesktopSecretStore internal constructor(
    private val file: File,
    private val keyring: Keyring?,
    private val protector: Protector?,
) : SecretStore {

    constructor(dir: File, osName: String = System.getProperty("os.name") ?: "") : this(
        File(dir, "secrets.json"),
        if (osName.lowercase().let { "win" !in it && "mac" !in it }) SecretToolKeyring() else null,
        if (osName.lowercase().let { "win" in it && "darwin" !in it }) DpapiProtector() else null,
    )

    override val backend: String
        get() = listOfNotNull(keyring?.name, protector?.name).joinToString(" / ").ifEmpty { "file only" } + " (fallback: file)"

    @Synchronized
    override fun set(key: String, value: String): Boolean {
        if (keyring?.store(key, value) == true) {
            dropFileEntry(key)
            return true
        }
        val encrypted = protector?.protect(value.toByteArray(StandardCharsets.UTF_8))
        if (encrypted != null) {
            putFileEntry(key, "dpapi", Base64.getEncoder().encodeToString(encrypted))
            return true
        }
        putFileEntry(key, "plain", value)
        return false
    }

    @Synchronized
    override fun get(key: String): String? {
        keyring?.lookup(key)?.let { return it }
        val entry = readFile()[key] ?: return null
        return when (entry.first) {
            "dpapi" -> protector?.unprotect(Base64.getDecoder().decode(entry.second))?.toString(StandardCharsets.UTF_8)
            else -> entry.second
        }
    }

    @Synchronized
    override fun remove(key: String) {
        keyring?.clear(key)
        dropFileEntry(key)
    }

    // MARK: File

    private fun readFile(): Map<String, Pair<String, String>> {
        if (!file.isFile) return emptyMap()
        return try {
            val root = Json.parseToJsonElement(file.readText(StandardCharsets.UTF_8)) as? JsonObject ?: return emptyMap()
            val out = LinkedHashMap<String, Pair<String, String>>()
            for ((k, e) in root) {
                val o = e as? JsonObject ?: continue
                val mode = (o["m"] as? JsonPrimitive)?.contentOrNull ?: continue
                val v = (o["v"] as? JsonPrimitive)?.contentOrNull ?: continue
                out[k] = mode to v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun putFileEntry(key: String, mode: String, value: String) = writeFile(readFile() + (key to (mode to value)))
    private fun dropFileEntry(key: String) { readFile().let { if (key in it) writeFile(it - key) } }

    private fun writeFile(entries: Map<String, Pair<String, String>>) {
        try {
            file.absoluteFile.parentFile?.mkdirs()
            val obj = JsonObject(entries.mapValues { (_, e) -> JsonObject(mapOf("m" to JsonPrimitive(e.first), "v" to JsonPrimitive(e.second))) })
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Json.encodeToString(JsonObject.serializer(), obj), StandardCharsets.UTF_8)
            runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            System.err.println("Pear: could not save secrets to ${file.path}: ${e.message}")
        }
    }
}

/** Linux Secret Service through `secret-tool` (package libsecret-tools). Every call is a short child process. */
internal class SecretToolKeyring(private val command: String = "secret-tool") : Keyring {
    override val name = "Secret Service"
    @Volatile private var missing = false

    private fun attrs(key: String) = listOf("service", "pear", "account", key)

    override fun store(key: String, value: String): Boolean = run(listOf("store", "--label=Pear ($key)") + attrs(key), stdin = value) != null

    override fun lookup(key: String): String? = run(listOf("lookup") + attrs(key))?.takeIf { it.isNotEmpty() }

    override fun clear(key: String) { run(listOf("clear") + attrs(key)) }

    /** Output of the command, or null when it failed, timed out or isn't installed. */
    private fun run(args: List<String>, stdin: String? = null): String? {
        if (missing) return null
        return try {
            val p = ProcessBuilder(listOf(command) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray(StandardCharsets.UTF_8)) } else p.outputStream.close()
            val out = p.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            if (p.exitValue() == 0) out else null
        } catch (_: IOException) {
            missing = true // not installed: stop trying
            null
        } catch (_: Exception) {
            null
        }
    }
}

/** Windows DPAPI through JNA (`CryptProtectData`). Output only opens for the same Windows user. */
internal class DpapiProtector : Protector {
    override val name = "Windows DPAPI"

    override fun protect(data: ByteArray): ByteArray? = try {
        com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(data)
    } catch (_: Throwable) {
        null
    }

    override fun unprotect(data: ByteArray): ByteArray? = try {
        com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(data)
    } catch (_: Throwable) {
        null
    }
}
