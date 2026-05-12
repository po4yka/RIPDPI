package com.poyka.ripdpi.core

object NativeEchTlsHandshakeBridge {
    fun connect(requestJson: String): String? {
        RipDpiNativeLoader.ensureLoaded()
        return jniConnect(requestJson)
    }

    @JvmStatic
    private external fun jniConnect(requestJson: String): String?
}
