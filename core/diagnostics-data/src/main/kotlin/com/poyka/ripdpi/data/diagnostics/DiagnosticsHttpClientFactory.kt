package com.poyka.ripdpi.data.diagnostics

import okhttp3.OkHttpClient

interface DiagnosticsHttpClientFactory {
    fun createClient(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient
}
