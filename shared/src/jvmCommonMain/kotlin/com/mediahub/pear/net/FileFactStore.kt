package com.mediahub.pear.net

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Fact files in one folder (the app's config folder, so they are not swept away like cache files). */
class FileFactStore(private val dir: File) : FactStore {
    override fun load(name: String): String? = try {
        File(dir, name).takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)
    } catch (_: IOException) {
        null
    }

    override fun save(name: String, text: String) {
        try {
            dir.mkdirs()
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text, StandardCharsets.UTF_8)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            System.err.println("Pear: could not save $name: ${e.message}")
        }
    }
}
