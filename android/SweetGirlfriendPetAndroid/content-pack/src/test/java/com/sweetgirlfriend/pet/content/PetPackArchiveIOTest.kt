package com.sweetgirlfriend.pet.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PetPackArchiveIOTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun extractsSafeArchiveAndComputesStableSha256() {
        val archive = zip("safe.petpack", "pack.json" to "{}", "data/value.txt" to "hello")
        val destination = temporaryFolder.newFolder("safe-out")

        val actual = PetPackArchiveIO.sha256(archive)
        PetPackArchiveIO.extractSafely(archive, destination)

        val expected = MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual)
        assertEquals("hello", File(destination, "data/value.txt").readText())
        assertFalse(File(destination, "../escape.txt").exists())
    }

    @Test
    fun extractsStandardZipWithExplicitDirectoryEntries() {
        val archive = temporaryFolder.newFile("explicit-directories.petpack")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/images/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/images/frame.txt"))
            zip.write("ok".toByteArray())
            zip.closeEntry()
        }
        val destination = temporaryFolder.newFolder("explicit-directories-out")

        PetPackArchiveIO.extractSafely(archive, destination)

        assertEquals("ok", File(destination, "assets/images/frame.txt").readText())
    }

    @Test
    fun rejectsUnsafeOrConflictingExplicitDirectoryEntries() {
        listOf("../", "assets//", "/", "bad\nname/", "assets\u202E/").forEachIndexed { index, directoryName ->
            val archive = temporaryFolder.newFile("unsafe-directory-$index.petpack")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(directoryName))
                zip.closeEntry()
            }
            assertTrue(
                runCatching {
                    PetPackArchiveIO.extractSafely(
                        archive,
                        temporaryFolder.newFolder("unsafe-directory-out-$index"),
                    )
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }

        val conflict = temporaryFolder.newFile("directory-file-conflict.petpack")
        ZipOutputStream(conflict.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets"))
            zip.write("not-a-directory".toByteArray())
            zip.closeEntry()
        }
        val error = runCatching {
            PetPackArchiveIO.extractSafely(conflict, temporaryFolder.newFolder("directory-file-conflict-out"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("重复路径"))
    }

    @Test
    fun hashingStopsPromptlyWhenScanThreadIsCancelled() {
        val archive = zip("cancelled-hash.petpack", "pack.json" to "{}")
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            Thread.currentThread().interrupt()
            failure.set(runCatching { PetPackArchiveIO.sha256(archive) }.exceptionOrNull())
        }

        worker.start()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(failure.get() is InterruptedException)
    }

    @Test
    fun rejectsPathTraversalBeforeWritingOutsideDestination() {
        val archive = zip("traversal.petpack", "../escape.txt" to "bad")
        val destination = temporaryFolder.newFolder("traversal-out")

        val error = runCatching { PetPackArchiveIO.extractSafely(archive, destination) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(File(destination.parentFile, "escape.txt").exists())
    }

    @Test
    fun rejectsWindowsAbsolutePathAndExecutablePayload() {
        val driveArchive = zip("drive.petpack", "C:/escape.txt" to "bad")
        val executableArchive = zip("executable.petpack", "payload/plugin.dex" to "bad")

        assertTrue(
            runCatching {
                PetPackArchiveIO.extractSafely(driveArchive, temporaryFolder.newFolder("drive-out"))
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                PetPackArchiveIO.extractSafely(executableArchive, temporaryFolder.newFolder("exe-out"))
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun rejectsInstallerReservedMetadataNames() {
        val archive = zip("reserved.petpack", ContentPackLoader.INSTALL_RECEIPT to "forged")

        val error = runCatching {
            PetPackArchiveIO.extractSafely(archive, temporaryFolder.newFolder("reserved-out"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("保留文件名"))
    }

    @Test
    fun rejectsSingleExpandedFileOverZipBombBudget() {
        val archive = temporaryFolder.newFile("large.petpack")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("large.bin"))
            val chunk = ByteArray(1024 * 1024)
            repeat(33) { zip.write(chunk) }
            zip.closeEntry()
        }

        val error = runCatching {
            PetPackArchiveIO.extractSafely(archive, temporaryFolder.newFolder("large-out"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("单个文件过大"))
    }

    @Test
    fun rejectsOversizedPackManifestBeforeJsonParsing() {
        val oversized = " ".repeat(PetPackJsonIO.MAX_MANIFEST_BYTES.toInt() + 1)
        val archive = zip("large-manifest.petpack", "pack.json" to oversized)

        val error = runCatching {
            PetPackArchiveIO.extractSafely(archive, temporaryFolder.newFolder("large-manifest-out"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("pack.json"))
        assertTrue(error?.message.orEmpty().contains("256 KiB"))
    }

    @Test
    fun rejectsOversizedChecksumsBeforeJsonParsing() {
        val oversized = " ".repeat(PetPackJsonIO.MAX_CHECKSUMS_BYTES.toInt() + 1)
        val archive = zip("large-checksums.petpack", "checksums.json" to oversized)

        val error = runCatching {
            PetPackArchiveIO.extractSafely(archive, temporaryFolder.newFolder("large-checksums-out"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("checksums.json"))
        assertTrue(error?.message.orEmpty().contains("1024 KiB"))
    }

    @Test
    fun expectedSha256RejectsChangedPrivateSnapshot() {
        val archive = zip("hash.petpack", "pack.json" to "{}")
        val originalSha = PetPackArchiveIO.sha256(archive)
        archive.appendBytes(byteArrayOf(1))

        val error = runCatching {
            PetPackArchiveIO.requireExpectedSha256(archive, originalSha)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("发生变化"))
    }

    @Test
    fun installGateSerializesConcurrentLanAndLocalCommits() {
        val workers = 12
        val pool = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        repeat(workers) {
            pool.execute {
                start.await()
                PackInstallGate.serialized {
                    val current = active.incrementAndGet()
                    maximumActive.updateAndGet { maximum -> maxOf(maximum, current) }
                    Thread.sleep(5)
                    active.decrementAndGet()
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, maximumActive.get())
    }

    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val archive = temporaryFolder.newFile(name)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            entries.forEach { (path, contents) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }
}
