package com.sweetgirlfriend.pet.content

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Bounded JSON reader shared by preflight, installation, and normal pack loading. */
internal object PetPackJsonIO {
    const val MAX_MANIFEST_BYTES = 256L * 1024
    const val MAX_CHECKSUMS_BYTES = 1024L * 1024
    const val MAX_STRUCTURED_JSON_BYTES = 4L * 1024 * 1024
    private const val MAX_INSTALL_RECEIPT_BYTES = 16L * 1024

    fun limitForPath(path: String): Long = when (path.substringAfterLast('/').lowercase()) {
        "pack.json" -> MAX_MANIFEST_BYTES
        "checksums.json" -> MAX_CHECKSUMS_BYTES
        ContentPackLoader.INSTALL_RECEIPT.lowercase() -> MAX_INSTALL_RECEIPT_BYTES
        else -> MAX_STRUCTURED_JSON_BYTES
    }

    fun isStructuredJson(path: String): Boolean = path.endsWith(".json", ignoreCase = true)

    fun read(file: File, logicalPath: String = file.name): JSONObject {
        require(file.isFile) { "JSON 文件不存在: $logicalPath" }
        return file.inputStream().use { read(it, logicalPath) }
    }

    fun read(stream: InputStream, logicalPath: String): JSONObject {
        val limit = limitForPath(logicalPath)
        val bytes = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE.toLong(), limit).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            PetPackCancellation.throwIfCancelled()
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) {
                "JSON 文件超过大小限制: $logicalPath (${limit / 1024} KiB)"
            }
            bytes.write(buffer, 0, count)
        }
        PetPackCancellation.throwIfCancelled()
        return JSONObject(bytes.toString(Charsets.UTF_8.name()))
    }
}
