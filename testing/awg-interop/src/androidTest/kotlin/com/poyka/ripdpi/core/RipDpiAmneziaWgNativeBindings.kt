package com.poyka.ripdpi.core

/** Exact production JNI ABI in a test host which has no Xray or VpnService dependencies. */
class RipDpiAmneziaWgNativeBindings {
    init {
        System.loadLibrary("ripdpi-amneziawg")
    }

    external fun jniCreate(configJson: String): Long

    external fun jniStart(handle: Long): Int

    external fun jniStop(handle: Long)

    external fun jniPollTelemetry(handle: Long): String?

    external fun jniDestroy(handle: Long)
}
