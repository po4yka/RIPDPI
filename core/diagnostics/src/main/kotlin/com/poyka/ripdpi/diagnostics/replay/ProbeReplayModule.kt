package com.poyka.ripdpi.diagnostics.replay

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt wiring for the probe-replay subsystem.
 *
 * - Binds [DefaultProbeReplayService] as the production [ProbeReplayService].
 * - Provides a [ReplayHttpClientFactory] that builds a per-call
 *   [OkHttpClient] routed through the local SOCKS5 proxy at
 *   `127.0.0.1:1080`, mirroring `OkHttpStrategyProbeTransport` in
 *   `StrategyProbeService.kt:302-310`.
 *
 * The factory constructs both the client AND the absolute `https://<domain>/`
 * URL per [ReplayHttpClient] — the URL depends on the request domain,
 * so it cannot be cached at factory construction time. The client is
 * built fresh per call because each replay sets its own
 * call/connect/read/write timeouts and attaches the caller's
 * [okhttp3.EventListener]; an OkHttpClient with a non-`null` event
 * listener cannot be safely shared across calls.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProbeReplayModule {
    @Binds
    @Singleton
    abstract fun bindProbeReplayService(impl: DefaultProbeReplayService): ProbeReplayService

    companion object {
        private const val ReplayProxyHost = "127.0.0.1"
        private const val ReplayProxyPort = 1080

        @Provides
        @Singleton
        fun provideReplayHttpClientFactory(): ReplayHttpClientFactory =
            ReplayHttpClientFactory { request, listener ->
                val client =
                    OkHttpClient
                        .Builder()
                        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(ReplayProxyHost, ReplayProxyPort)))
                        .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                        .connectTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                        .writeTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                        .eventListener(listener)
                        .build()
                ReplayHttpClient(
                    client = client,
                    url = "https://${request.domain}/".toHttpUrl(),
                )
            }
    }
}
