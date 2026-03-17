package com.poyka.ripdpi.core

import android.content.Context

internal object RipDpiNativeLoader {
    @Volatile private var platformTlsInitialized = false

    init {
        System.loadLibrary("ripdpi")
    }

    fun ensureLoaded() {
        // Accessing this object triggers the init block (library load).
    }

    fun ensureLoaded(context: Context) {
        if (!platformTlsInitialized) {
            synchronized(this) {
                if (!platformTlsInitialized) {
                    jniInitPlatformTls(context.applicationContext)
                    platformTlsInitialized = true
                }
            }
        }
    }

    private external fun jniInitPlatformTls(context: Any)
}
