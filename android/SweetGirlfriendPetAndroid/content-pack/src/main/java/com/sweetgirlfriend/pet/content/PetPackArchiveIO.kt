package com.sweetgirlfriend.pet.content

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Pure file/archive operations kept separate so their security limits can be JVM tested. */
internal object PetPackArchiveIO {
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    private const val MAX_SINGLE_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_FILES = 2000
    private val BLOCKED_EXTENSIONS = setOf(
        "apk", "dex", "jar", "class", "so", "exe", "dll", "sh", "bat", "cmd", "ps1",
    )
    private val RESERVED_ROOT_FILES = setOf(
        ContentPackLoader.INSTALL_MARKER,
        ContentPackLoader.INSTALL_RECEIPT,
    )
    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")

    fun requireSupportedArchive(archive: File) {
        require(archive.isFile) { "资源包文件不存在" }
        require(archive.length() in 1..ContentPackInstaller.MAX_ARCHIVE_BYTES) {
            "资源包大小必须小于 ${ContentPackInstaller.MAX_ARCHIVE_BYTES / 1024 / 1024} MB"
        }
    }

    fun sha256(archive: File): String {
        requireSupportedArchive(archive)
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                PetPackCancellation.throwIfCancelled()
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun requireExpectedSha256(archive: File, expectedSha256: String): String {
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "预期的 SHA-256 格式无效" }
        val actual = sha256(archive)
        require(actual.equals(expectedSha256, ignoreCase = true)) {
            "资源包文件已在确认后发生变化"
        }
        return actual
    }

    fun extractSafely(archive: File, destinationRoot: File) {
        var fileCount = 0
        var expandedBytes = 0L
        val seenPaths = mutableSetOf<String>()
        val canonicalRoot = destinationRoot.canonicalFile
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                PetPackCancellation.throwIfCancelled()
                val entry = zip.nextEntry ?: break
                fileCount += 1
                require(fileCount <= MAX_FILES) { "资源包文件数超过 $MAX_FILES" }
                val rawName = entry.name
                val directory = entry.isDirectory
                val name = if (directory) rawName.dropLast(1) else rawName
                require(isSafeRelativePath(name)) { "不安全的归档路径: $name" }
                require(name !in RESERVED_ROOT_FILES) { "归档使用了应用保留文件名: $name" }
                require(seenPaths.add(name)) { "归档包含重复路径: $name" }
                require(!isExecutablePayload(name)) { "资源包不能包含可执行文件: $name" }
                val output = File(destinationRoot, name).canonicalFile
                require(output.path.startsWith(canonicalRoot.path + File.separator)) { "归档路径越界" }
                if (directory) {
                    require(output.mkdirs() || output.isDirectory) { "无法创建目录: $name" }
                    continue
                }
                val entryLimit = if (PetPackJsonIO.isStructuredJson(name)) {
                    PetPackJsonIO.limitForPath(name)
                } else {
                    MAX_SINGLE_FILE_BYTES
                }
                if (entry.size >= 0) {
                    require(entry.size <= entryLimit) { sizeLimitMessage(name, entryLimit) }
                }
                require(output.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                    "无法创建资源目录"
                }
                var fileBytes = 0L
                FileOutputStream(output).use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        PetPackCancellation.throwIfCancelled()
                        val count = zip.read(buffer)
                        if (count < 0) break
                        fileBytes += count
                        expandedBytes += count
                        require(fileBytes <= entryLimit) { sizeLimitMessage(name, entryLimit) }
                        require(expandedBytes <= MAX_EXPANDED_BYTES) { "资源包展开大小超过限制" }
                        destination.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.contains('\\') &&
            !path.contains('\u0000') &&
            !containsUnsafeFormatting(path) &&
            !WINDOWS_DRIVE_PATH.matches(path) &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun containsUnsafeFormatting(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (
                Character.isISOControl(codePoint) ||
                Character.getType(codePoint) == Character.FORMAT.toInt()
            ) return true
            offset += Character.charCount(codePoint)
        }
        return false
    }

    private fun isExecutablePayload(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in BLOCKED_EXTENSIONS

    private fun sizeLimitMessage(path: String, limit: Long): String =
        if (PetPackJsonIO.isStructuredJson(path)) {
            "JSON 文件超过大小限制: $path (${limit / 1024} KiB)"
        } else {
            "单个文件过大: $path"
        }
}
