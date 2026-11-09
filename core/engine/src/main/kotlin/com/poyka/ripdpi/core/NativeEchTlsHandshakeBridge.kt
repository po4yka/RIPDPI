package com.poyka.ripdpi.core

object NativeEchTlsHandshakeBridge {
    fun createRequest(): Long {
        RipDpiNativeLoader.ensureLoaded()
        return jniCreateRequest()
    }

    fun connect(
        requestHandle: Long,
        requestJson: String,
    ): String? {
        RipDpiNativeLoader.ensureLoaded()
        return jniConnect(requestHandle, requestJson)
    }

    fun cancelRequest(requestHandle: Long) {
        RipDpiNativeLoader.ensureLoaded()
        jniCancelRequest(requestHandle)
    }

    fun releaseRequest(requestHandle: Long) {
        RipDpiNativeLoader.ensureLoaded()
        jniReleaseRequest(requestHandle)
    }

    @JvmStatic
    private external fun jniCreateRequest(): Long

    @JvmStatic
    private external fun jniConnect(
        requestHandle: Long,
        requestJson: String,
    ): String?

    @JvmStatic
    private external fun jniCancelRequest(requestHandle: Long)

    @JvmStatic
    private external fun jniReleaseRequest(requestHandle: Long)
}
