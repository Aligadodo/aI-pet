package com.sweetgirlfriend.pet.content

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class PackIntegrityValidatorTest {
    @Test
    fun acceptsSha256ManifestThatExactlyCoversPackFiles() {
        val content = mapOf("pack.json" to "manifest", "data/value.txt" to "value")

        val errors = validate(content, content.keys)

        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun rejectsUnchecksummedExtraFile() {
        val declared = mapOf("pack.json" to "manifest")

        val errors = validate(declared, setOf("pack.json", "hidden/extra.txt"))

        assertTrue(errors.any { it.contains("does not cover files") })
    }

    @Test
    fun rejectsWrongAlgorithmAndUnsafeDeclaredPath() {
        val wrongAlgorithm = integrity(mapOf("pack.json" to "manifest")).put("algorithm", "MD5")
        val algorithmErrors = PackIntegrityValidator.validate(
            wrongAlgorithm,
            "checksums.json",
            setOf("pack.json"),
        ) { ByteArrayInputStream("manifest".toByteArray()) }
        val unsafe = integrity(mapOf("../escape" to "payload"))
        val unsafeErrors = PackIntegrityValidator.validate(
            unsafe,
            "checksums.json",
            setOf("../escape"),
        ) { ByteArrayInputStream("payload".toByteArray()) }

        assertTrue(algorithmErrors.any { it.contains("algorithm") })
        assertTrue(unsafeErrors.any { it.contains("Unsafe integrity path") })
    }

    private fun validate(declared: Map<String, String>, actual: Set<String>): List<String> =
        PackIntegrityValidator.validate(
            integrity = integrity(declared),
            integrityPath = "checksums.json",
            actualFiles = actual + "checksums.json",
            openFile = { path -> ByteArrayInputStream(declared.getValue(path).toByteArray()) },
        )

    private fun integrity(files: Map<String, String>): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("algorithm", "SHA-256")
        .put(
            "files",
            JSONObject().apply {
                files.forEach { (path, value) -> put(path, value.toByteArray().sha256()) }
            },
        )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
