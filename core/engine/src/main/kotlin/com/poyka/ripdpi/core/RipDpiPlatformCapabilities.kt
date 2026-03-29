package com.poyka.ripdpi.core

object RipDpiPlatformCapabilities {
    @Volatile private var cachedSeqOverlapSupported: Boolean? = null

    fun seqovlSupported(): Boolean {
        cachedSeqOverlapSupported?.let { return it }
        return synchronized(this) {
            cachedSeqOverlapSupported ?: querySeqovlSupported().also { cachedSeqOverlapSupported = it }
        }
    }

    private fun querySeqovlSupported(): Boolean =
        try {
            RipDpiNativeLoader.ensureLoaded()
            jniSeqovlSupported()
        } catch (_: LinkageError) {
            false
        }

    private external fun jniSeqovlSupported(): Boolean
}
