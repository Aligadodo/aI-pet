package com.sweetgirlfriend.pet.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.provider.DocumentsContract
import com.sweetgirlfriend.pet.content.ContentPackInstaller
import com.sweetgirlfriend.pet.content.ContentPackLoader
import com.sweetgirlfriend.pet.content.PackInspectionResult
import com.sweetgirlfriend.pet.content.PackInstallResult
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Device-side release gate for a real `.petpack` archive.
 *
 * This instrumentation lives only in the separate androidTest APK; it is never packaged in the
 * user-facing application. The host gate first asks for a writable staging directory, pushes one
 * archive there, and then invokes the full production inspector/installer/loader stack.
 */
class PetPackInstallGateInstrumentation : Instrumentation() {
    private var arguments: Bundle = Bundle.EMPTY

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle.EMPTY
        start()
    }

    override fun onStart() {
        var resultCode = Activity.RESULT_CANCELED
        var resultMessage = "PETPACK_GATE_FAIL IllegalStateException: gate did not complete"
        var isolatedRoot: File? = null
        try {
            if (arguments.getString(ARG_VIVO_SCANNER_COMPAT_ONLY).toBoolean()) {
                isolatedRoot = File(
                    targetContext.cacheDir,
                    "vivo-provider-scanner-gate-${UUID.randomUUID()}",
                )
                require(isolatedRoot.mkdirs()) { "Unable to create isolated scanner root" }
                resultMessage = runVivoScannerCompatibilityGate(isolatedRoot)
                resultCode = Activity.RESULT_OK
            } else if (arguments.getString(ARG_PREPARE_ONLY).toBoolean()) {
                val staging = stagingDirectory()
                resultCode = Activity.RESULT_OK
                resultMessage = "PETPACK_GATE_STAGING=${staging.absolutePath}"
            } else {
                val archivePath = arguments.getString(ARG_PACK_PATH).orEmpty()
                val expectedSha256 = arguments.getString(ARG_EXPECTED_SHA256).orEmpty()
                require(archivePath.isNotBlank()) { "Missing -e $ARG_PACK_PATH <path>" }
                require(SHA_256.matches(expectedSha256)) {
                    "Missing or invalid -e $ARG_EXPECTED_SHA256 <64 hex characters>"
                }
                val archive = File(archivePath)
                require(archive.isFile) { "PetPack archive is not readable: $archivePath" }
                val stagedSha256 = sha256(archive)
                require(stagedSha256.equals(expectedSha256, ignoreCase = true)) {
                    "PetPack archive SHA-256 mismatch: " +
                        "expected ${expectedSha256.lowercase()}, actual $stagedSha256"
                }

                isolatedRoot =
                    File(targetContext.cacheDir, "petpack-install-gate-${UUID.randomUUID()}")
                require(isolatedRoot.mkdirs()) { "Unable to create isolated install root" }
                val gateContext = GateContext(targetContext, isolatedRoot)
                val installer = ContentPackInstaller(gateContext)

                val inspection = installer.inspect(archive)
                require(inspection is PackInspectionResult.Ready) {
                    "Preflight failed: ${(inspection as PackInspectionResult.Failure).message}"
                }
                require(inspection.archiveSha256.equals(expectedSha256, ignoreCase = true)) {
                    "Preflight archive SHA-256 changed after staging"
                }
                val installed = installer.install(archive, inspection)
                require(installed is PackInstallResult.Success && !installed.unchanged) {
                    "Initial install failed: $installed"
                }

                // A fresh repository instance simulates a process restart/cold reload.
                val coldLoader = ContentPackLoader(gateContext)
                val validationErrors = coldLoader.validate(inspection.packId)
                require(validationErrors.isEmpty()) {
                    "Cold-load validation failed: ${validationErrors.joinToString(" | ")}"
                }
                val descriptor = coldLoader.loadDescriptor(inspection.packId)
                require(descriptor.version == inspection.version) { "Cold-load version mismatch" }
                val actions = coldLoader.availableActions(inspection.packId)
                require("idle" in actions) { "Installed pack has no idle action" }
                actions.forEach { action ->
                    require(coldLoader.loadClip(inspection.packId, action).framePaths.isNotEmpty()) {
                        "Action $action has no frames after cold load"
                    }
                }
                val tasks = coldLoader.loadTasks(inspection.packId)

                val duplicateInspection = installer.inspect(archive)
                require(duplicateInspection is PackInspectionResult.Ready) {
                    "Duplicate preflight failed"
                }
                require(duplicateInspection.isDuplicateContent) {
                    "Duplicate content was not recognized"
                }
                val duplicateInstall = installer.install(archive, duplicateInspection)
                require(duplicateInstall is PackInstallResult.Success && duplicateInstall.unchanged) {
                    "Duplicate install was not idempotent: $duplicateInstall"
                }

                resultCode = Activity.RESULT_OK
                resultMessage =
                    "PETPACK_GATE_PASS id=${inspection.packId} version=${inspection.version} " +
                    "actions=${actions.size} tasks=${tasks.size}"
            }
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            resultMessage =
                "PETPACK_GATE_FAIL ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        } finally {
            isolatedRoot?.let { root ->
                val removed = runCatching { root.deleteRecursively() && !root.exists() }
                    .getOrDefault(false)
                if (!removed) {
                    resultCode = Activity.RESULT_CANCELED
                    resultMessage =
                        "PETPACK_GATE_FAIL CleanupException: isolated install root remains: " +
                        root.absolutePath
                }
            }
        }
        finishWithResult(resultCode, resultMessage)
    }

    private fun runVivoScannerCompatibilityGate(root: File): String {
        // Use the instrumentation APK's context so its non-exported fake provider remains
        // accessible without shipping the fixture in the user-facing debug APK.
        val scannerContext = ScannerGateContext(requireNotNull(context), root)
        scannerContext.contentResolver.call(
            VivoInvalidColumnProvider.DOCUMENT_URI,
            VivoInvalidColumnProvider.METHOD_RESET,
            null,
            null,
        )
        try {
            val report = LocalPetPackScanner(scannerContext).scanDocuments(
                listOf(VivoInvalidColumnProvider.DOCUMENT_URI),
            )
            val stats = requireNotNull(
                scannerContext.contentResolver.call(
                    VivoInvalidColumnProvider.DOCUMENT_URI,
                    VivoInvalidColumnProvider.METHOD_STATS,
                    null,
                    null,
                ),
            ) { "Fake provider did not return query statistics" }
            val projections = stats.getStringArrayList(VivoInvalidColumnProvider.KEY_PROJECTIONS)
                .orEmpty()
                .map { it.split(VivoInvalidColumnProvider.PROJECTION_SEPARATOR) }
            require(
                projections.any {
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED in it
                } && stats.getInt(VivoInvalidColumnProvider.KEY_REJECTED_LAST_MODIFIED) > 0,
            ) { "Scanner never exercised the provider's rejected last_modified projection" }
            require(report.snapshots.size == 1) {
                "Expected one candidate, got ${report.snapshots.size}: ${report.diagnostics}"
            }
            val snapshot = report.snapshots.single()
            val expectedSnapshotRoot =
                File(scannerContext.cacheDir, "local-petpack-scan").canonicalFile
            require(snapshot.file.canonicalFile.toPath().startsWith(expectedSnapshotRoot.toPath())) {
                "Snapshot escaped the app-private cache: ${snapshot.file}"
            }
            require(snapshot.file.readBytes().contentEquals(VivoInvalidColumnProvider.payloadBytes())) {
                "Private snapshot content differs from provider stream"
            }
            require(snapshot.sourceSize == VivoInvalidColumnProvider.payloadBytes().size.toLong()) {
                "Snapshot size does not match the actual provider stream"
            }
            require(snapshot.lastModified == null) {
                "Unsupported last_modified must degrade to null"
            }
            require(report.diagnostics.none { it.contains("无法读取") }) {
                "Optional metadata rejection was reported as unreadable: ${report.diagnostics}"
            }
            require(report.diagnostics.any { it.contains("修改时间") }) {
                "The optional metadata downgrade was not reported: ${report.diagnostics}"
            }
            return "VIVO_SCANNER_COMPAT_PASS snapshots=${report.snapshots.size} " +
                "bytes=${snapshot.file.length()} sha256=${snapshot.sha256}"
        } finally {
            scannerContext.contentResolver.call(
                VivoInvalidColumnProvider.DOCUMENT_URI,
                VivoInvalidColumnProvider.METHOD_RESET,
                null,
                null,
            )
        }
    }

    private fun stagingDirectory(): File {
        val externalRoot = targetContext.getExternalFilesDir(null)
            ?: error("App external files directory is unavailable")
        return File(externalRoot, "petpack-release-gate").also {
            require(it.mkdirs() || it.isDirectory) { "Unable to create PetPack gate staging directory" }
        }
    }

    private fun finishWithResult(code: Int, message: String) {
        sendStatus(code, Bundle().apply { putString("stream", "$message\n") })
        finish(code, Bundle().apply { putString("stream", "$message\n") })
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class GateContext(base: Context, root: File) : ContextWrapper(base) {
        private val isolatedFiles = File(root, "files").apply { mkdirs() }
        private val isolatedCache = File(root, "cache").apply { mkdirs() }

        override fun getFilesDir(): File = isolatedFiles

        override fun getCacheDir(): File = isolatedCache

        override fun getApplicationContext(): Context = this
    }

    private class ScannerGateContext(
        base: Context,
        root: File,
    ) : ContextWrapper(base) {
        private val isolatedCache = File(root, "scanner-cache").apply { mkdirs() }

        override fun getCacheDir(): File = isolatedCache

        override fun getApplicationContext(): Context = this
    }

    companion object {
        private const val ARG_PREPARE_ONLY = "prepareOnly"
        private const val ARG_PACK_PATH = "packPath"
        private const val ARG_EXPECTED_SHA256 = "expectedSha256"
        private const val ARG_VIVO_SCANNER_COMPAT_ONLY = "vivoScannerCompatOnly"
        private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
