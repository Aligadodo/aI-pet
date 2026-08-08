package com.sweetgirlfriend.pet.content

import android.content.res.AssetManager
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContentPackInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installThenInspectRecognizesExactAndContentDuplicateFromReceipt() {
        val installer = installer()
        val archive = createPack("sample-pack", "1.0.0", "first")
        val first = ready(installer.inspect(archive))

        val installed = installer.install(archive, first)
        val duplicate = ready(installer.inspect(archive))

        assertTrue(installed is PackInstallResult.Success)
        assertEquals(PackVersionRelation.SAME, duplicate.relationToInstalled)
        assertTrue(duplicate.isDuplicateVersion)
        assertTrue(duplicate.isDuplicateContent)
        assertTrue(duplicate.isExactArchiveDuplicate)
        assertFalse(duplicate.sameVersionDifferentContent)
        assertEquals(first.archiveSha256, duplicate.installedArchiveSha256)
        assertEquals("Unverified Author", duplicate.author)
        val skipped = installer.install(archive, duplicate)
        assertTrue(skipped is PackInstallResult.Success && skipped.unchanged)
    }

    @Test
    fun sameVersionDifferentContentIsReportedAndLanStyleInstallUpdatesReceipt() {
        val installer = installer()
        val firstArchive = createPack("conflict-pack", "1.0.0", "first")
        val secondArchive = createPack("conflict-pack", "1.0.0", "second")
        assertTrue(installer.install(firstArchive) is PackInstallResult.Success)

        val conflict = ready(installer.inspect(secondArchive))

        assertEquals(PackVersionRelation.SAME, conflict.relationToAvailable)
        assertTrue(conflict.sameVersionDifferentContent)
        assertFalse(conflict.isDuplicateContent)
        assertFalse(conflict.isExactArchiveDuplicate)
        assertTrue(installer.install(secondArchive) is PackInstallResult.Success)
        val after = ready(installer.inspect(secondArchive))
        assertTrue(after.isExactArchiveDuplicate)
        assertTrue(after.isDuplicateContent)
    }

    @Test
    fun staleConfirmationRejectsSameVersionContentChangedByAnotherInstaller() {
        val installer = installer()
        val base = createPack("race-pack", "1.0.0", "base")
        val pending = createPack("race-pack", "1.0.0", "pending")
        val concurrent = createPack("race-pack", "1.0.0", "concurrent")
        assertTrue(installer.install(base) is PackInstallResult.Success)
        val staleInspection = ready(installer.inspect(pending))
        assertTrue(installer.install(concurrent) is PackInstallResult.Success)

        val result = installer.install(pending, staleInspection)

        assertTrue(result is PackInstallResult.Failure)
        assertTrue((result as PackInstallResult.Failure).message.contains("重新扫描"))
        assertEquals(
            ready(installer.inspect(concurrent)).archiveSha256,
            ready(installer.inspect(concurrent)).installedArchiveSha256,
        )
    }

    @Test
    fun allInstallEntryPointsRejectDowngradeFromHighestInstalledVersion() {
        val installer = installer()
        val current = createPack("version-pack", "2.0.0", "new")
        val downgrade = createPack("version-pack", "1.0.0", "old")
        assertTrue(installer.install(current) is PackInstallResult.Success)
        val inspection = ready(installer.inspect(downgrade))
        assertEquals(PackVersionRelation.DOWNGRADE, inspection.relationToAvailable)

        val lanStyle = installer.install(downgrade)
        val confirmedStyle = installer.install(downgrade, inspection)

        assertTrue(lanStyle is PackInstallResult.Failure)
        assertTrue(confirmedStyle is PackInstallResult.Failure)
        assertTrue((lanStyle as PackInstallResult.Failure).message.contains("降级"))
        assertEquals("2.0.0", ready(installer.inspect(current)).installedVersion)
    }

    @Test
    fun inspectionDoesNotCreateInstalledPackOrReceipt() {
        val installer = installer()
        val archive = createPack("read-only-pack", "1.0.0", "payload")

        val inspected = ready(installer.inspect(archive))

        assertFalse(inspected.replacesExisting)
        assertNull(inspected.installedVersion)
        assertNull(inspected.installedArchiveSha256)
        val installedRoot = File(contextRoot, "files/content-packs")
        assertTrue(installedRoot.listFiles().isNullOrEmpty())
    }

    private lateinit var contextRoot: File

    private fun installer(): ContentPackInstaller {
        contextRoot = temporaryFolder.newFolder("context-${System.nanoTime()}")
        val context = FileBackedContext(contextRoot)
        return ContentPackInstaller(context) { _, _, _ -> emptyList() }
    }

    private fun ready(result: PackInspectionResult): PackInspectionResult.Ready {
        assertTrue(result is PackInspectionResult.Ready)
        return result as PackInspectionResult.Ready
    }

    private fun createPack(packId: String, version: String, payload: String): File {
        val pack = JSONObject()
            .put("schemaVersion", 2)
            .put(
                "protocol",
                JSONObject()
                    .put("id", "io.sweetpet.pack")
                    .put("version", "2.0")
                    .put("minRuntime", "0.5.0"),
            )
            .put("id", packId)
            .put("name", "Test Pack")
            .put("author", "Unverified Author")
            .put("version", version)
            .put("integrity", "checksums.json")
            .toString()
        val files = linkedMapOf(
            "pack.json" to pack.toByteArray(),
            "data/value.txt" to payload.toByteArray(),
        )
        val checksums = JSONObject()
            .put("schemaVersion", 1)
            .put("algorithm", "SHA-256")
            .put(
                "files",
                JSONObject().apply {
                    files.forEach { (path, bytes) -> put(path, bytes.sha256()) }
                },
            )
            .toString()
            .toByteArray()
        val archive = temporaryFolder.newFile("$packId-$version-${payload.hashCode()}.petpack")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            (files + ("checksums.json" to checksums)).forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private class FileBackedContext(root: File) : ContextWrapper(null) {
        private val files = File(root, "files").apply { mkdirs() }
        private val cache = File(root, "cache").apply { mkdirs() }

        override fun getFilesDir(): File = files

        override fun getCacheDir(): File = cache

        override fun getApplicationContext(): FileBackedContext = this

        override fun getAssets(): AssetManager = throw FileNotFoundException("No bundled test assets")
    }
}
