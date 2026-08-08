package com.sweetgirlfriend.pet.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.sweetgirlfriend.pet.content.ContentPackInstaller
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class LocalPackSnapshot(
    val file: File,
    val sourceUri: Uri,
    val displayName: String,
    val sourcePath: String,
    val sourceSize: Long?,
    val lastModified: Long?,
    val sha256: String,
    val sourceFingerprint: String,
)

internal data class LocalPackScanReport(
    val snapshots: List<LocalPackSnapshot>,
    val diagnostics: List<String>,
    val visitedEntries: Int,
    val duplicateContents: Int,
)

internal data class LocalPackScanLimits(
    val maxDepth: Int = 5,
    val maxEntries: Int = 1_200,
    val maxCandidates: Int = 80,
    val maxTotalSnapshotBytes: Long = 384L * 1024 * 1024,
)

/**
 * Reads only user-selected Storage Access Framework documents. It deliberately
 * has no path-based or broad-storage fallback.
 */
internal class LocalPetPackScanner(
    context: Context,
    private val limits: LocalPackScanLimits = LocalPackScanLimits(),
) {
    private val applicationContext = context.applicationContext
    private val resolver: ContentResolver = applicationContext.contentResolver
    private val snapshotRoot = File(applicationContext.cacheDir, "local-petpack-scan")

    fun scanDocuments(uris: List<Uri>): LocalPackScanReport {
        val diagnostics = mutableListOf<String>()
        val unsupportedOptionalColumns = mutableSetOf<String>()
        val sources = uris.distinct().mapNotNull { uri ->
            readSelectedDocument(uri, diagnostics, unsupportedOptionalColumns)
        }.take(limits.maxCandidates)
        if (uris.distinct().size > limits.maxCandidates) {
            diagnostics += "一次最多处理 ${limits.maxCandidates} 个候选文件，其余文件已忽略。"
        }
        return snapshot(sources, diagnostics, visitedEntries = uris.distinct().size)
    }

    fun scanTree(treeUri: Uri): LocalPackScanReport {
        val diagnostics = mutableListOf<String>()
        val sources = mutableListOf<PackDocument>()
        val visitedDocumentIds = mutableSetOf<String>()
        val unsupportedOptionalColumns = mutableSetOf<String>()
        var visitedEntries = 0
        var stopped = false

        fun walk(parentDocumentId: String, relativePath: String, depth: Int) {
            ensureScanningActive()
            if (stopped) return
            if (depth > limits.maxDepth) {
                diagnostics += "目录层级超过 ${limits.maxDepth} 层，已停止继续向下扫描：$relativePath"
                return
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            runCatching {
                resolver.query(
                    childrenUri,
                    LocalPackMetadataPolicy.treeRequiredProjection(),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (!stopped && cursor.moveToNext()) {
                        ensureScanningActive()
                        visitedEntries += 1
                        if (visitedEntries > limits.maxEntries) {
                            stopped = true
                            diagnostics += "目录条目超过 ${limits.maxEntries} 个，扫描已按安全预算停止。"
                            break
                        }
                        val documentId = cursor.stringOrNull(idColumn) ?: continue
                        if (!visitedDocumentIds.add(documentId)) continue
                        val rawName = cursor.stringOrNull(nameColumn)?.ifBlank { "未命名文档" } ?: "未命名文档"
                        val name = LocalPackScanPolicy.safeUiText(rawName)
                        val mime = cursor.stringOrNull(mimeColumn).orEmpty()
                        val path = if (relativePath.isBlank()) name else "$relativePath/$name"
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(documentId, path, depth + 1)
                        } else if (LocalPackScanPolicy.isPetPackName(rawName)) {
                            if (sources.size >= limits.maxCandidates) {
                                stopped = true
                                diagnostics += "找到的 .petpack 超过 ${limits.maxCandidates} 个，扫描已停止。"
                                break
                            }
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                            sources += PackDocument(
                                uri = documentUri,
                                originalName = rawName,
                                displayName = name,
                                sourcePath = path,
                                size = readOptionalLongMetadata(
                                    documentUri,
                                    DocumentsContract.Document.COLUMN_SIZE,
                                    diagnostics,
                                    unsupportedOptionalColumns,
                                ),
                                lastModified = readOptionalLongMetadata(
                                    documentUri,
                                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                                    diagnostics,
                                    unsupportedOptionalColumns,
                                ),
                            )
                        }
                    }
                } ?: diagnostics.add("文档提供方没有返回可扫描的目录内容。")
            }.onFailure { error ->
                if (error is InterruptedException) throw error
                diagnostics += "无法读取目录 ${relativePath.ifBlank { "根目录" }}：${safeMessage(error)}"
            }
        }

        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrElse { error ->
            return LocalPackScanReport(emptyList(), listOf("目录授权无效：${safeMessage(error)}"), 0, 0)
        }
        visitedDocumentIds += rootId
        walk(rootId, "", 0)
        return snapshot(sources, diagnostics, visitedEntries)
    }

    private fun readSelectedDocument(
        uri: Uri,
        diagnostics: MutableList<String>,
        unsupportedOptionalColumns: MutableSet<String>,
    ): PackDocument? {
        val queriedName = runCatching {
            ensureScanningActive()
            resolver.query(
                uri,
                LocalPackMetadataPolicy.selectedNameProjection(),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.stringOrNull(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            }.also { ensureScanningActive() }
        }.getOrElse { error ->
            if (error is InterruptedException || Thread.currentThread().isInterrupted) throw InterruptedException("扫描已取消")
            diagnostics += "无法读取所选文件名，已尝试使用文档地址继续识别：${safeMessage(error)}"
            null
        }
        val rawName = queriedName
            ?.ifBlank { uri.lastPathSegment.orEmpty() }
            ?: uri.lastPathSegment.orEmpty()
        val name = LocalPackScanPolicy.safeUiText(rawName)
        if (!LocalPackScanPolicy.isPetPackName(rawName)) {
            diagnostics += "已忽略非 .petpack 文件：$name"
            return null
        }
        val metadata = PackDocument(
            uri = uri,
            originalName = rawName,
            displayName = name,
            sourcePath = name,
            size = readOptionalLongMetadata(
                uri,
                OpenableColumns.SIZE,
                diagnostics,
                unsupportedOptionalColumns,
            ),
            lastModified = readOptionalLongMetadata(
                uri,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                diagnostics,
                unsupportedOptionalColumns,
            ),
        )
        return metadata
    }

    private fun readOptionalLongMetadata(
        uri: Uri,
        column: String,
        diagnostics: MutableList<String>,
        unsupportedOptionalColumns: MutableSet<String>,
    ): Long? {
        val supportKey = "${uri.authority.orEmpty()}\n$column"
        if (supportKey in unsupportedOptionalColumns) return null
        return LocalPackMetadataPolicy.readOptional(
            query = {
                ensureScanningActive()
                resolver.query(uri, LocalPackMetadataPolicy.optionalProjection(column), null, null, null)
                    ?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        cursor.longOrNull(cursor.getColumnIndex(column))
                    }
                    .also { ensureScanningActive() }
            },
            onFailure = {
                if (unsupportedOptionalColumns.add(supportKey)) {
                    val label = if (column == DocumentsContract.Document.COLUMN_LAST_MODIFIED) {
                        "修改时间"
                    } else {
                        "文件大小"
                    }
                    diagnostics += "文档提供方不支持可选的${label}元数据，已继续按实际文件内容校验。"
                }
            },
        )
    }

    private fun snapshot(
        sources: List<PackDocument>,
        diagnostics: MutableList<String>,
        visitedEntries: Int,
    ): LocalPackScanReport {
        val session = newSessionDirectory()
        return try {
            val results = mutableListOf<LocalPackSnapshot>()
            val seenHashes = mutableSetOf<String>()
            var duplicateContents = 0
            var totalBytes = 0L

            sources.forEachIndexed { index, source ->
                ensureScanningActive()
                if (source.size != null && source.size !in 1..ContentPackInstaller.MAX_ARCHIVE_BYTES) {
                    diagnostics += "${source.sourcePath} 大小不合法（单包上限 96 MB）。"
                    return@forEachIndexed
                }
                val target = File(session, "%03d-%s.petpack".format(index, UUID.randomUUID()))
                val digest = MessageDigest.getInstance("SHA-256")
                var fileBytes = 0L
                val copied = runCatching {
                    resolver.openInputStream(source.uri)?.use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                ensureScanningActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                fileBytes += count
                                totalBytes += count
                                require(fileBytes <= ContentPackInstaller.MAX_ARCHIVE_BYTES) {
                                    "文件超过 96 MB 上限"
                                }
                                require(totalBytes <= limits.maxTotalSnapshotBytes) {
                                    "本次扫描累计读取超过 ${limits.maxTotalSnapshotBytes / 1024 / 1024} MB"
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("文档提供方无法打开文件")
                    require(fileBytes > 0) { "文件为空" }
                    true
                }.getOrElse { error ->
                    if (error is InterruptedException) throw error
                    target.delete()
                    diagnostics += "读取 ${source.sourcePath} 失败：${safeMessage(error)}"
                    false
                }
                if (!copied) return@forEachIndexed
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!seenHashes.add(sha256)) {
                    duplicateContents += 1
                    target.delete()
                    diagnostics += "已合并内容相同的候选：${source.sourcePath}"
                    return@forEachIndexed
                }
                target.setReadOnly()
                val fingerprint = LocalPackScanPolicy.sourceFingerprint(
                    uri = source.uri.toString(),
                    lastModified = source.lastModified,
                    size = fileBytes,
                )
                results += LocalPackSnapshot(
                    file = target,
                    sourceUri = source.uri,
                    displayName = source.displayName,
                    sourcePath = source.sourcePath,
                    sourceSize = fileBytes,
                    lastModified = source.lastModified,
                    sha256 = sha256,
                    sourceFingerprint = fingerprint,
                )
            }
            LocalPackScanReport(results, diagnostics, visitedEntries, duplicateContents)
        } catch (error: Throwable) {
            session.deleteRecursively()
            throw error
        }
    }

    private fun newSessionDirectory(): File {
        snapshotRoot.mkdirs()
        // A previous Activity may still be unwinding a canceled provider call. Never delete
        // another live session here; each owner removes its own snapshots explicitly.
        snapshotRoot.listFiles()
            ?.filter {
                LocalPackScanPolicy.isStaleSession(
                    lastModified = it.lastModified(),
                    now = System.currentTimeMillis(),
                    maxAgeMs = STALE_SESSION_AGE_MS,
                )
            }
            ?.forEach { it.deleteRecursively() }
        return File(snapshotRoot, UUID.randomUUID().toString()).also {
            require(it.mkdirs()) { "无法创建本地资源包扫描缓存" }
        }
    }

    private data class PackDocument(
        val uri: Uri,
        val originalName: String,
        val displayName: String,
        val sourcePath: String,
        val size: Long?,
        val lastModified: Long?,
    )

    private fun safeMessage(error: Throwable): String = LocalPackScanPolicy.safeUiText(
        error.message?.ifBlank { null } ?: error.javaClass.simpleName,
        maxLength = 180,
    )

    private fun ensureScanningActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("扫描已取消")
    }

    companion object {
        private const val STALE_SESSION_AGE_MS = 24L * 60 * 60 * 1000
    }
}

internal object LocalPackMetadataPolicy {
    fun treeRequiredProjection(): Array<String> = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    fun selectedNameProjection(): Array<String> = arrayOf(OpenableColumns.DISPLAY_NAME)

    fun optionalProjection(column: String): Array<String> = arrayOf(column)

    fun <T> readOptional(query: () -> T?, onFailure: (Exception) -> Unit = {}): T? {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("扫描已取消")
        return try {
            query().also {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("扫描已取消")
            }
        } catch (error: Exception) {
            if (error is InterruptedException || Thread.currentThread().isInterrupted) {
                throw InterruptedException("扫描已取消")
            }
            onFailure(error)
            null
        }
    }
}

internal object LocalPackScanPolicy {
    fun isPetPackName(name: String): Boolean {
        val suffixLength = ".petpack".length
        if (name.length <= suffixLength || !name.endsWith(".petpack", ignoreCase = true)) return false
        return (0 until name.length - suffixLength).any { !name[it].isWhitespace() }
    }

    fun sourceFingerprint(uri: String, lastModified: Long?, size: Long?): String {
        val raw = "$uri\n${lastModified ?: -1L}\n${size ?: -1L}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun safeUiText(value: String, maxLength: Int = 120): String {
        val cleaned = buildString(value.length.coerceAtMost(maxLength)) {
            var previousWasSpace = false
            value.forEach { char ->
                val normalized = when {
                    char == '\n' || char == '\r' || char == '\t' -> ' '
                    char.isISOControl() || char.category == CharCategory.FORMAT -> return@forEach
                    else -> char
                }
                if (normalized.isWhitespace()) {
                    if (!previousWasSpace) append(' ')
                    previousWasSpace = true
                } else {
                    append(normalized)
                    previousWasSpace = false
                }
                if (length >= maxLength) return@buildString
            }
        }.trim()
        return cleaned.ifBlank { "未命名" }
    }

    fun wasConfirmed(
        sha256: String,
        sourceFingerprint: String,
        confirmedHashes: Set<String>,
        confirmedSources: Set<String>,
    ): Boolean = sha256 in confirmedHashes || "$sourceFingerprint:$sha256" in confirmedSources

    fun isStaleSession(lastModified: Long, now: Long, maxAgeMs: Long): Boolean =
        lastModified > 0L && now >= lastModified && now - lastModified > maxAgeMs
}

private fun android.database.Cursor.stringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

private fun android.database.Cursor.longOrNull(index: Int): Long? =
    if (index < 0 || isNull(index)) null else getLong(index).takeIf { it >= 0L }
