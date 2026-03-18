package com.poyka.ripdpi.core

internal object RipDpiNativeLoader {
    init {
        System.loadLibrary("ripdpi")
    }

    fun ensureLoaded() {
        // Accessing this object triggers the init block (library load).
    }
}
