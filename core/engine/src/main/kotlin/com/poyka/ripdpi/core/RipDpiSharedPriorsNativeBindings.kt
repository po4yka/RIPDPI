package com.poyka.ripdpi.core

import android.util.Base64

// JNI bindings for the shared-priors transport.
//
// The Kotlin worker fetches a manifest + priors payload from the
// release channel and calls `applySharedPriors`; the native side
// validates the manifest's ed25519 signature, parses the NDJSON
// payload, and writes the resulting prior store into the process-wide
// registry. The native side returns a JSON status string for the
// worker to log.
internal object RipDpiSharedPriorsNativeBindings {
    init {
        RipDpiNativeLoader.ensureLoaded()
    }

    // Apply a verified shared-priors bundle to the global registry.
    // Returns the native status JSON: `{"ok": true, "count": N}` on
    // success, `{"ok": false, "error": "..."}` on any rejection.
    fun applySharedPriors(
        manifestJson: String,
        priorsBytes: ByteArray,
    ): String {
        val priorsBase64 = Base64.encodeToString(priorsBytes, Base64.NO_WRAP)
        return jniApplySharedPriors(manifestJson, priorsBase64) ?: "{\"ok\":false,\"error\":\"native_returned_null\"}"
    }

    @Suppress("LongParameterList")
    @JvmStatic
    private external fun jniApplySharedPriors(
        manifestJson: String,
        priorsBase64: String,
    ): String?
}
