package com.sweetgirlfriend.pet.content

import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/** PetPack v2 checksum and exact-file-set policy, independent of Android storage. */
internal object PackIntegrityValidator {
    private val SHA_256 = Regex("[a-fA-F0-9]{64}")

    fun validate(
        integrity: JSONObject,
        integrityPath: String,
        actualFiles: Set<String>,
        openFile: (String) -> InputStream,
    ): List<String> {
        if (integrity.optInt("schemaVersion", -1) != 1) {
            return listOf("integrity: unsupported checksums schema")
        }
        if (integrity.optString("algorithm", "") != "SHA-256") {
            return listOf("integrity: algorithm must be SHA-256")
        }
        val files = runCatching { integrity.getJSONObject("files") }
            .getOrElse { return listOf("integrity: ${it.message}") }
        return buildList {
            val declared = mutableSetOf<String>()
            for (relative in files.keys()) {
                PetPackCancellation.throwIfCancelled()
                runCatching {
                    requireSafeRelativePath(relative)
                    require(relative != integrityPath) { "checksums.json cannot checksum itself" }
                    val expected = files.getString(relative)
                    require(SHA_256.matches(expected)) { "Invalid SHA-256 value: $relative" }
                    declared += relative
                    val digest = MessageDigest.getInstance("SHA-256")
                    openFile(relative).use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            PetPackCancellation.throwIfCancelled()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actual.equals(expected, ignoreCase = true)) {
                        "SHA-256 mismatch: $relative"
                    }
                }.onFailure { add(it.message ?: "Integrity check failed: $relative") }
            }
            val contentFiles = actualFiles - integrityPath
            val missing = declared - contentFiles
            val unexpected = contentFiles - declared
            if (missing.isNotEmpty()) {
                add("Integrity list references missing files: ${missing.sorted().joinToString()}")
            }
            if (unexpected.isNotEmpty()) {
                add("Integrity list does not cover files: ${unexpected.sorted().joinToString()}")
            }
        }
    }

    private fun requireSafeRelativePath(path: String) {
        require(
            path.isNotBlank() &&
                !path.startsWith('/') &&
                !path.contains('\\') &&
                !path.contains('\u0000') &&
                !Regex("^[A-Za-z]:.*").matches(path) &&
                path.split('/').none { it.isBlank() || it == "." || it == ".." },
        ) { "Unsafe integrity path: $path" }
    }
}
