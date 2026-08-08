package com.sweetgirlfriend.pet.content

/** Cooperative cancellation shared by SAF scanning, preflight hashing, and archive validation. */
internal object PetPackCancellation {
    fun throwIfCancelled() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("资源包扫描已取消")
        }
    }
}
