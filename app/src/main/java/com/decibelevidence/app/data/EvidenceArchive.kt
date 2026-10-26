package com.decibelevidence.app.data

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Relative UTF-8 filenames; the manifest itself is intentionally not self-hashed. */
object EvidenceArchive {
    fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun create(source: File, target: File, includedNames: Set<String>? = null) {
        require(source.isDirectory)
        require(!target.canonicalPath.startsWith(source.canonicalPath + File.separator))
        val files = source.walkTopDown().filter {
            it.isFile && !it.name.endsWith(".part") && it.name != "SHA256SUMS.txt" &&
                (includedNames == null || it.relativeTo(source).invariantSeparatorsPath in includedNames)
        }.sortedBy { it.relativeTo(source).invariantSeparatorsPath }.toList()
        check(includedNames == null || files.map { it.relativeTo(source).invariantSeparatorsPath }.toSet() == includedNames) { "导出所需文件缺失" }
        val manifest = files.joinToString("\n", postfix = "\n") {
            "${sha256(it)}  ${it.relativeTo(source).invariantSeparatorsPath}"
        }
        File(source, "SHA256SUMS.txt").writeText(manifest, Charsets.UTF_8)
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        try {
            ZipOutputStream(partial.outputStream().buffered(), Charsets.UTF_8).use { zip ->
                (files + File(source, "SHA256SUMS.txt")).forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            check(partial.renameTo(target)) { "无法完成导出文件" }
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }
    fun verify(source: File): List<String> = File(source, "SHA256SUMS.txt").readLines().filter { it.isNotBlank() }.mapNotNull {
        val expected = it.substringBefore("  ")
        val name = it.substringAfter("  ")
        val file = File(source, name)
        if (!file.canonicalPath.startsWith(source.canonicalPath + File.separator) || !file.isFile || sha256(file) != expected) name else null
    }
}
