package com.poyka.ripdpi.jni

import com.poyka.ripdpi.core.Tun2SocksNativeLoader

internal object PcapBridge {
    init {
        Tun2SocksNativeLoader.ensureLoaded()
    }

    external fun jniPcapStart(
        sessionHandle: Long,
        captureDir: String,
        maxFileBytes: Long,
        maxFiles: Int,
    ): Long

    external fun jniPcapStop(
        sessionHandle: Long,
        finishedOnly: Boolean,
    ): String?

    external fun jniPcapListCaptures(
        captureDir: String,
        cleanupOnly: Boolean,
    ): String?

    external fun jniPcapRedactToFile(
        source: String,
        destFd: Int,
    ): Long
}
