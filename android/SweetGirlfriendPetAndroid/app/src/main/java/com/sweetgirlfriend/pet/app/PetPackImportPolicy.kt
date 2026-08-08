package com.sweetgirlfriend.pet.app

import java.util.Locale

/** Pure policy for external PetPack imports and SAF tree choices. */
internal object PetPackImportPolicy {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    fun isExternalImportAction(action: String?): Boolean = action in setOf(
        ACTION_VIEW,
        ACTION_SEND,
        ACTION_SEND_MULTIPLE,
    )

    /** Incoming files must use a grantable ContentProvider URI, never a raw path or network URL. */
    fun acceptsIncomingScheme(scheme: String?): Boolean = scheme.equals("content", ignoreCase = true)

    fun rejectedTreeReason(authority: String?, documentId: String): RejectedTreeReason? {
        val normalized = documentId.trim().replace('\\', '/').lowercase(Locale.ROOT)
        if (normalized.isBlank()) return RejectedTreeReason.STORAGE_ROOT

        // ExternalStorageProvider volume roots use ids such as "primary:" or "ABCD-1234:".
        if (!normalized.contains('/') && normalized.endsWith(':')) {
            return RejectedTreeReason.STORAGE_ROOT
        }

        val provider = authority.orEmpty().lowercase(Locale.ROOT)
        if (
            normalized == "download" || normalized == "downloads" ||
            (provider.contains("downloads") && normalized.substringAfter(':') in setOf("download", "downloads"))
        ) {
            return RejectedTreeReason.DOWNLOAD_ROOT
        }

        val logicalPath = normalized.substringAfter(':', normalized).trim('/')
        if (logicalPath == "download" || logicalPath.endsWith("/download")) {
            return RejectedTreeReason.DOWNLOAD_ROOT
        }

        val boundedPath = "/$logicalPath/"
        if ("/android/data/" in boundedPath || "/android/obb/" in boundedPath) {
            return RejectedTreeReason.ANDROID_PRIVATE_DIRECTORY
        }
        return null
    }
}

internal enum class RejectedTreeReason {
    STORAGE_ROOT,
    DOWNLOAD_ROOT,
    ANDROID_PRIVATE_DIRECTORY,
}
