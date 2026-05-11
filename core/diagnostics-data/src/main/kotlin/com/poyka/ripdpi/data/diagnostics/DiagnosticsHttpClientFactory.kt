package com.poyka.ripdpi.data.diagnostics

import okhttp3.OkHttpClient

data class DiagnosticsTlsClientState(
    val profileId: String?,
    val nativeOwnedTlsAvailable: Boolean,
    val fallbackActive: Boolean,
    val fallbackReason: String?,
)

interface DiagnosticsHttpClientFactory {
    fun createClient(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient

    fun tlsClientState(): DiagnosticsTlsClientState =
        DiagnosticsTlsClientState(
            profileId = null,
            nativeOwnedTlsAvailable = false,
            fallbackActive = false,
            fallbackReason = null,
        )
}
