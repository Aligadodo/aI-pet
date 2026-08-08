package com.sweetgirlfriend.pet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetPackImportPolicyTest {
    @Test
    fun `only supported external import actions are accepted`() {
        assertTrue(PetPackImportPolicy.isExternalImportAction(PetPackImportPolicy.ACTION_VIEW))
        assertTrue(PetPackImportPolicy.isExternalImportAction(PetPackImportPolicy.ACTION_SEND))
        assertTrue(PetPackImportPolicy.isExternalImportAction(PetPackImportPolicy.ACTION_SEND_MULTIPLE))
        assertFalse(PetPackImportPolicy.isExternalImportAction("android.intent.action.MAIN"))
        assertFalse(PetPackImportPolicy.isExternalImportAction(null))
    }

    @Test
    fun `incoming imports require a grantable content uri`() {
        assertTrue(PetPackImportPolicy.acceptsIncomingScheme("content"))
        assertTrue(PetPackImportPolicy.acceptsIncomingScheme("CONTENT"))
        assertFalse(PetPackImportPolicy.acceptsIncomingScheme("file"))
        assertFalse(PetPackImportPolicy.acceptsIncomingScheme("http"))
        assertFalse(PetPackImportPolicy.acceptsIncomingScheme(null))
    }

    @Test
    fun `storage volume roots are rejected`() {
        assertEquals(
            RejectedTreeReason.STORAGE_ROOT,
            PetPackImportPolicy.rejectedTreeReason("com.android.externalstorage.documents", "primary:"),
        )
        assertEquals(
            RejectedTreeReason.STORAGE_ROOT,
            PetPackImportPolicy.rejectedTreeReason("com.android.externalstorage.documents", "ABCD-1234:"),
        )
    }

    @Test
    fun `download roots from common document providers are rejected`() {
        listOf(
            "primary:Download",
            "raw:/storage/emulated/0/Download",
            "download",
            "downloads",
        ).forEach { documentId ->
            assertEquals(
                RejectedTreeReason.DOWNLOAD_ROOT,
                PetPackImportPolicy.rejectedTreeReason("com.android.providers.downloads.documents", documentId),
            )
        }
    }

    @Test
    fun `android private trees are rejected`() {
        listOf(
            "primary:Android/data",
            "primary:Android/data/example",
            "raw:/storage/emulated/0/Android/obb/game",
        ).forEach { documentId ->
            assertEquals(
                RejectedTreeReason.ANDROID_PRIVATE_DIRECTORY,
                PetPackImportPolicy.rejectedTreeReason("com.android.externalstorage.documents", documentId),
            )
        }
    }

    @Test
    fun `ordinary subdirectories remain selectable`() {
        listOf(
            "primary:Download/PetPacks",
            "raw:/storage/emulated/0/Download/PetPacks",
            "primary:Documents/PetPacks",
            "ABCD-1234:PetPacks",
        ).forEach { documentId ->
            assertNull(
                documentId,
                PetPackImportPolicy.rejectedTreeReason("com.android.externalstorage.documents", documentId),
            )
        }
    }
}
