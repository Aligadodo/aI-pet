package com.sweetgirlfriend.pet.content

import android.content.Context
import com.sweetgirlfriend.pet.runtime.PetPackManifestProtocol
import com.sweetgirlfriend.pet.runtime.StablePackVersion
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed interface PackInstallResult {
    data class Success(
        val packId: String,
        val name: String,
        val version: String,
        val replaced: Boolean,
        /** True when identical checksummed content was already active and no filesystem swap ran. */
        val unchanged: Boolean = false,
    ) : PackInstallResult

    data class Failure(val message: String) : PackInstallResult
}

/** How the candidate version compares with an already available copy of the same pack. */
enum class PackVersionRelation {
    NOT_PRESENT,
    SAME,
    UPGRADE,
    DOWNGRADE,
}

/**
 * Result of a read-only archive inspection. A [Ready] result means the complete pack loader,
 * including checksum and bitmap-budget validation, accepted the archive.
 */
sealed interface PackInspectionResult {
    data class Ready(
        val packId: String,
        val name: String,
        /** Self-declared author metadata. It is informational and is not a trust assertion. */
        val author: String?,
        val version: String,
        val archiveSha256: String,
        val archiveSizeBytes: Long,
        /** Canonical digest of the complete checksums.json file set, independent of ZIP encoding. */
        val contentSha256: String,
        val replacesExisting: Boolean,
        val installedVersion: String?,
        val installedArchiveSha256: String?,
        val bundledVersion: String?,
        val relationToInstalled: PackVersionRelation,
        val relationToAvailable: PackVersionRelation,
        val availableContentSha256: String?,
    ) : PackInspectionResult {
        /** Same id and semantic version are already installed or bundled. */
        val isDuplicateVersion: Boolean
            get() = relationToAvailable == PackVersionRelation.SAME

        /** The already active copy has the same semantic version and the same checksummed content. */
        val isDuplicateContent: Boolean
            get() = isDuplicateVersion && contentSha256 == availableContentSha256

        /** Same id/version but different checksummed bytes must receive an explicit conflict warning. */
        val sameVersionDifferentContent: Boolean
            get() = isDuplicateVersion && availableContentSha256 != null && contentSha256 != availableContentSha256

        /** Exact ZIP duplicate of the installed file, when a modern installation receipt exists. */
        val isExactArchiveDuplicate: Boolean
            get() = installedArchiveSha256 != null &&
                archiveSha256.equals(installedArchiveSha256, ignoreCase = true)
    }

    data class Failure(val message: String) : PackInspectionResult
}

/** Serializes the final filesystem swap used by LAN and local-file installation entry points. */
internal object PackInstallGate {
    private val monitor = Any()

    fun <T> serialized(block: () -> T): T = synchronized(monitor) { block() }
}

class ContentPackInstaller internal constructor(
    private val context: Context,
    private val packValidator: (Context, File, String) -> List<String>,
) {
    constructor(context: Context) : this(
        context,
        { validationContext, validationRoot, packId ->
            ContentPackLoader.forInstalledRoot(validationContext, validationRoot).validate(packId)
        },
    )

    private val installedRoot = File(context.filesDir, "content-packs")

    /**
     * Fully validates [archive] without installing it or changing preferences. Temporary files are
     * kept below the app-private cache directory and are removed before this method returns.
     */
    fun inspect(archive: File): PackInspectionResult = runCatching {
        val prepared = prepareArchive(
            archive = archive,
            workspaceParent = File(context.cacheDir, INSPECTION_CACHE_DIRECTORY),
            expectedSha256 = null,
        )
        try {
            PackInstallGate.serialized {
                val installedVersion = readInstalledVersion(prepared.packId)
                val availableVersion = highestVersion(installedVersion, prepared.bundledVersion)
                val installedReceipt = readInstalledReceipt(prepared.packId)
                PackInspectionResult.Ready(
                    packId = prepared.packId,
                    name = prepared.name,
                    author = prepared.author,
                    version = prepared.versionText,
                    archiveSha256 = prepared.archiveSha256,
                    archiveSizeBytes = prepared.archiveSizeBytes,
                    contentSha256 = prepared.contentSha256,
                    replacesExisting = File(installedRoot, prepared.packId).exists(),
                    installedVersion = installedVersion,
                    installedArchiveSha256 = installedReceipt?.archiveSha256,
                    bundledVersion = prepared.bundledVersion,
                    relationToInstalled = compareVersions(prepared.version, installedVersion),
                    relationToAvailable = compareVersions(prepared.version, availableVersion),
                    availableContentSha256 = readAvailableContentSha256(
                        packId = prepared.packId,
                        installedVersion = installedVersion,
                        bundledVersion = prepared.bundledVersion,
                    ),
                )
            }
        } finally {
            prepared.workspace.deleteRecursively()
        }
    }.getOrElse { error ->
        if (error is InterruptedException) throw error
        PackInspectionResult.Failure(error.message ?: "资源包预检失败")
    }

    /** Alias kept for UI call sites that describe this operation as a preflight. */
    fun preflight(archive: File): PackInspectionResult = inspect(archive)

    fun install(
        archive: File,
        expectedSha256: String? = null,
    ): PackInstallResult = PackInstallGate.serialized {
        installSerialized(archive, expectedSha256, expectedInspection = null)
    }

    /**
     * Installs the exact app-private snapshot that produced [inspection]. Besides rechecking the
     * hash, this rejects a stale confirmation if another installer changed the active pack first.
     */
    fun install(
        archive: File,
        inspection: PackInspectionResult.Ready,
    ): PackInstallResult = PackInstallGate.serialized {
        installSerialized(archive, inspection.archiveSha256, expectedInspection = inspection)
    }

    private fun installSerialized(
        archive: File,
        expectedSha256: String?,
        expectedInspection: PackInspectionResult.Ready?,
    ): PackInstallResult = runCatching {
        installedRoot.mkdirs()
        expectedInspection?.let { expected ->
            require(readInstalledVersion(expected.packId) == expected.installedVersion) {
                "资源包已被其他安装任务更新，请重新扫描并确认"
            }
            require(readBundledVersion(expected.packId) == expected.bundledVersion) {
                "应用内置资源包已变更，请重新扫描并确认"
            }
            require(
                readAvailableContentSha256(
                    expected.packId,
                    expected.installedVersion,
                    expected.bundledVersion,
                ) == expected.availableContentSha256,
            ) { "资源包内容已被其他安装任务更新，请重新扫描并确认" }
            require(readInstalledReceipt(expected.packId)?.archiveSha256 == expected.installedArchiveSha256) {
                "资源包安装回执已变更，请重新扫描并确认"
            }
        }
        val prepared = prepareArchive(
            archive = archive,
            workspaceParent = installedRoot,
            expectedSha256 = expectedSha256,
        )
        try {
            expectedInspection?.let { expected ->
                require(
                    prepared.packId == expected.packId &&
                        prepared.name == expected.name &&
                        prepared.versionText == expected.version &&
                        prepared.contentSha256 == expected.contentSha256,
                ) { "资源包内容与确认时不一致，请重新扫描" }
            }

            val installedVersion = readInstalledVersion(prepared.packId)
            val availableVersion = highestVersion(installedVersion, prepared.bundledVersion)
            val relationToAvailable = compareVersions(prepared.version, availableVersion)
            require(relationToAvailable != PackVersionRelation.DOWNGRADE) {
                "拒绝将资源包降级到 ${prepared.versionText}，当前可用版本为 $availableVersion"
            }
            val availableContentSha256 = readAvailableContentSha256(
                packId = prepared.packId,
                installedVersion = installedVersion,
                bundledVersion = prepared.bundledVersion,
            )
            if (relationToAvailable == PackVersionRelation.SAME &&
                availableContentSha256 != null &&
                availableContentSha256 == prepared.contentSha256
            ) {
                return@runCatching PackInstallResult.Success(
                    packId = prepared.packId,
                    name = prepared.name,
                    version = prepared.versionText,
                    replaced = File(installedRoot, prepared.packId).exists(),
                    unchanged = true,
                )
            }
            val nonce = UUID.randomUUID().toString()
            val target = File(installedRoot, prepared.packId)
            val backup = File(installedRoot, ".backup-${prepared.packId}-$nonce")
            val replaced = target.exists()
            if (replaced) require(target.renameTo(backup)) { "无法备份旧资源包" }
            var targetCreated = false
            try {
                writeInstallReceipt(prepared)
                require(prepared.packRoot.renameTo(target)) { "无法提交资源包" }
                targetCreated = true
                backup.deleteRecursively()
                PackInstallResult.Success(
                    packId = prepared.packId,
                    name = prepared.name,
                    version = prepared.versionText,
                    replaced = replaced,
                )
            } catch (error: Throwable) {
                if (targetCreated) target.deleteRecursively()
                if (backup.exists()) backup.renameTo(target)
                throw error
            }
        } finally {
            prepared.workspace.deleteRecursively()
        }
    }.getOrElse { PackInstallResult.Failure(it.message ?: "资源包安装失败") }

    private fun prepareArchive(
        archive: File,
        workspaceParent: File,
        expectedSha256: String?,
    ): PreparedArchive {
        PetPackCancellation.throwIfCancelled()
        PetPackArchiveIO.requireSupportedArchive(archive)
        val archiveSha256 = PetPackArchiveIO.sha256(archive)
        expectedSha256?.let { expected ->
            require(SHA_256.matches(expected)) { "预期的 SHA-256 格式无效" }
            require(archiveSha256.equals(expected, ignoreCase = true)) {
                "资源包文件已在确认后发生变化"
            }
        }

        require(workspaceParent.mkdirs() || workspaceParent.isDirectory) {
            "无法创建资源包临时目录"
        }
        val workspace = File(workspaceParent, ".petpack-work-${UUID.randomUUID()}")
        require(workspace.mkdirs()) { "无法创建资源包预检目录" }
        var completed = false
        try {
            val unpacked = File(workspace, "unpacked")
            require(unpacked.mkdirs()) { "无法创建解包目录" }
            PetPackArchiveIO.extractSafely(archive, unpacked)
            PetPackCancellation.throwIfCancelled()
            val metadata = validateManifest(unpacked)
            val packRoot = File(workspace, metadata.packId)
            require(unpacked.renameTo(packRoot)) { "无法准备资源包验证目录" }
            File(packRoot, ContentPackLoader.INSTALL_MARKER).writeText("petpack-v2\n", Charsets.UTF_8)

            val errors = packValidator(context, workspace, metadata.packId)
            PetPackCancellation.throwIfCancelled()
            require(errors.isEmpty()) { errors.take(5).joinToString("；") }
            completed = true
            return PreparedArchive(
                workspace = workspace,
                packRoot = packRoot,
                packId = metadata.packId,
                name = metadata.name,
                author = metadata.author,
                versionText = metadata.versionText,
                version = metadata.version,
                bundledVersion = metadata.bundledVersion,
                archiveSha256 = archiveSha256,
                archiveSizeBytes = archive.length(),
                contentSha256 = contentSha256(File(packRoot, "checksums.json")),
            )
        } finally {
            if (!completed) workspace.deleteRecursively()
        }
    }

    private fun validateManifest(packRoot: File): ManifestMetadata {
        val manifestFile = File(packRoot, "pack.json")
        require(manifestFile.isFile) { "资源包根目录缺少 pack.json" }
        val manifest = PetPackJsonIO.read(manifestFile, "pack.json")
        require(manifest.optInt("schemaVersion") == 2) { "仅支持 PetPack v2" }
        val protocol = manifest.optJSONObject("protocol")
        PetPackManifestProtocol.resolveMinRuntime(
            schemaVersion = 2,
            protocolId = protocol?.opt("id") as? String,
            protocolVersion = protocol?.opt("version") as? String,
            protocolMinRuntime = protocol?.opt("minRuntime") as? String,
            legacyMinRuntime = "0.1.0",
        )
        val packId = manifest.getString("id")
        require(SAFE_ID.matches(packId)) { "资源包 id 不安全" }
        require(manifest.optString("integrity") == "checksums.json") {
            "PetPack v2 必须提供 checksums.json"
        }
        require(File(packRoot, "checksums.json").isFile) { "缺少完整性清单" }
        val versionText = manifest.getString("version")
        val version = StablePackVersion.parseRequired(versionText)
        val name = PetPackMetadataPolicy.requireName(manifest.getString("name"))
        val author = manifest.optString("author", "")
            .takeIf(String::isNotBlank)
            ?.let(PetPackMetadataPolicy::requireAuthor)
        val bundledVersionText = readBundledVersion(packId)
        val bundledVersion = bundledVersionText?.let {
            StablePackVersion.parseRequired(it, "Bundled pack version")
        }
        require(bundledVersion == null || version >= bundledVersion) {
            "资源包版本 $version 低于应用内置版本 $bundledVersion，请选择同版本或更新版本"
        }
        return ManifestMetadata(
            packId = packId,
            name = name,
            author = author,
            versionText = versionText,
            version = version,
            bundledVersion = bundledVersionText,
        )
    }

    private fun readInstalledVersion(packId: String): String? {
        val root = File(installedRoot, packId)
        if (!root.isDirectory || !File(root, ContentPackLoader.INSTALL_MARKER).isFile) return null
        return runCatching {
            PetPackJsonIO.read(File(root, "pack.json"), "pack.json").getString("version")
        }.getOrNull()?.takeIf { StablePackVersion.parseOrNull(it) != null }
    }

    private fun readBundledVersion(packId: String): String? = runCatching {
        context.assets.open("packs/$packId/pack.json").use {
            PetPackJsonIO.read(it, "packs/$packId/pack.json").getString("version")
        }
    }.getOrNull()

    private fun readInstalledReceipt(packId: String): InstallReceipt? = runCatching {
        val root = File(installedRoot, packId)
        if (!File(root, ContentPackLoader.INSTALL_MARKER).isFile) return@runCatching null
        val json = PetPackJsonIO.read(
            File(root, ContentPackLoader.INSTALL_RECEIPT),
            ContentPackLoader.INSTALL_RECEIPT,
        )
        require(json.optInt("schemaVersion") == 1)
        val archiveSha256 = json.getString("archiveSha256")
        require(SHA_256.matches(archiveSha256))
        val archiveSizeBytes = json.getLong("archiveSizeBytes")
        require(archiveSizeBytes in 1..MAX_ARCHIVE_BYTES)
        val version = json.getString("version")
        StablePackVersion.parseRequired(version, "Installation receipt version")
        require(version == readInstalledVersion(packId))
        InstallReceipt(
            archiveSha256 = archiveSha256.lowercase(),
            archiveSizeBytes = archiveSizeBytes,
            version = version,
        )
    }.getOrNull()

    private fun writeInstallReceipt(prepared: PreparedArchive) {
        val receipt = JSONObject()
            .put("schemaVersion", 1)
            .put("archiveSha256", prepared.archiveSha256)
            .put("archiveSizeBytes", prepared.archiveSizeBytes)
            .put("version", prepared.versionText)
        File(prepared.packRoot, ContentPackLoader.INSTALL_RECEIPT)
            .writeText(receipt.toString(), Charsets.UTF_8)
    }

    private fun readAvailableContentSha256(
        packId: String,
        installedVersion: String?,
        bundledVersion: String?,
    ): String? {
        val installed = installedVersion?.let(StablePackVersion::parseOrNull)
        val bundled = bundledVersion?.let(StablePackVersion::parseOrNull)
        return if (installed != null && (bundled == null || installed >= bundled)) {
            runCatching { contentSha256(File(installedRoot, "$packId/checksums.json")) }.getOrNull()
        } else {
            runCatching {
                context.assets.open("packs/$packId/checksums.json").use {
                    contentSha256(PetPackJsonIO.read(it, "packs/$packId/checksums.json"))
                }
            }.getOrNull()
        }
    }

    private fun contentSha256(file: File): String =
        contentSha256(PetPackJsonIO.read(file, "checksums.json"))

    private fun contentSha256(root: JSONObject): String {
        val files = root.getJSONObject("files")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        files.keys().asSequence().sorted().forEach { path ->
            val checksum = files.getString(path).lowercase()
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(checksum.toByteArray(Charsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun highestVersion(first: String?, second: String?): String? {
        val firstVersion = first?.let(StablePackVersion::parseOrNull)
        val secondVersion = second?.let(StablePackVersion::parseOrNull)
        return when {
            firstVersion == null -> second
            secondVersion == null -> first
            firstVersion >= secondVersion -> first
            else -> second
        }
    }

    private fun compareVersions(
        candidate: StablePackVersion,
        existingVersion: String?,
    ): PackVersionRelation {
        val existing = existingVersion?.let(StablePackVersion::parseOrNull)
            ?: return PackVersionRelation.NOT_PRESENT
        return when {
            candidate == existing -> PackVersionRelation.SAME
            candidate > existing -> PackVersionRelation.UPGRADE
            else -> PackVersionRelation.DOWNGRADE
        }
    }

    private data class ManifestMetadata(
        val packId: String,
        val name: String,
        val author: String?,
        val versionText: String,
        val version: StablePackVersion,
        val bundledVersion: String?,
    )

    private data class PreparedArchive(
        val workspace: File,
        val packRoot: File,
        val packId: String,
        val name: String,
        val author: String?,
        val versionText: String,
        val version: StablePackVersion,
        val bundledVersion: String?,
        val archiveSha256: String,
        val archiveSizeBytes: Long,
        val contentSha256: String,
    )

    private data class InstallReceipt(
        val archiveSha256: String,
        val archiveSizeBytes: Long,
        val version: String,
    )

    companion object {
        const val MAX_ARCHIVE_BYTES = 96L * 1024 * 1024
        private const val INSPECTION_CACHE_DIRECTORY = "petpack-inspections"
        private val SAFE_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val SHA_256 = Regex("[a-fA-F0-9]{64}")
    }
}
