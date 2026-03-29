package com.poyka.ripdpi.core

object RipDpiPlatformCapabilities {
    @Volatile private var cachedSeqOverlapSupported: Boolean? = null

    init {
        RipDpiNativeLoader.ensureLoaded()
    }

    fun seqovlSupported(): Boolean {
        cachedSeqOverlapSupported?.let { return it }
        return synchronized(this) {
            cachedSeqOverlapSupported ?: jniSeqovlSupported().also { cachedSeqOverlapSupported = it }
        }
    }

    private external fun jniSeqovlSupported(): Boolean
}
