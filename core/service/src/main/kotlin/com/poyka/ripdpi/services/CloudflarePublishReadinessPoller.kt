@file:Suppress("MagicNumber")

package com.poyka.ripdpi.services

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

private const val CloudflarePublishMetricsReadyPath = "/ready"
private const val CloudflarePublishReadyPollIntervalMs = 100L
private const val CloudflarePublishReadyTimeoutMs = 7_500L

internal class CloudflarePublishReadinessPoller
    @Inject
    constructor() {
        suspend fun waitForOriginReady(state: RunningCloudflarePublish) {
            val deadline = System.currentTimeMillis() + CloudflarePublishReadyTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!isCloudflareProcessAlive(state.originProcess.process)) {
                    throw IOException(state.lastError ?: "Cloudflare origin helper exited before readiness")
                }
                if (state.originReadySignal.isCompleted) {
                    state.originListenerAddress = state.originReadySignal.await()
                    return
                }
                delay(CloudflarePublishReadyPollIntervalMs)
            }
            throw IOException("Cloudflare origin helper readiness timed out")
        }

        suspend fun waitForCloudflaredReady(state: RunningCloudflarePublish) {
            val deadline = System.currentTimeMillis() + CloudflarePublishReadyTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!isCloudflareProcessAlive(state.cloudflaredProcess.process)) {
                    throw IOException(state.lastError ?: "cloudflared exited before readiness")
                }
                if (cloudflaredReady(state.metricsAddress)) {
                    return
                }
                delay(CloudflarePublishReadyPollIntervalMs)
            }
            throw IOException("cloudflared readiness timed out")
        }

        private fun cloudflaredReady(metricsAddress: String): Boolean =
            runCatching {
                val connection =
                    (
                        URL("http://$metricsAddress$CloudflarePublishMetricsReadyPath")
                            .openConnection() as HttpURLConnection
                    )
                connection.connectTimeout = 300
                connection.readTimeout = 300
                connection.requestMethod = "GET"
                connection.connect()
                (connection.responseCode in 200..299).also {
                    connection.disconnect()
                }
            }.getOrDefault(false)
    }
