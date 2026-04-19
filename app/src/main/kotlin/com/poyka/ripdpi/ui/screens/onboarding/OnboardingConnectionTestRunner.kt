package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.activities.ConnectionTestState
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

private const val httpSuccessCodeMin = 200
private const val httpSuccessCodeMax = 399

@Singleton
class OnboardingConnectionTestRunner
    @Inject
    constructor(
        private val tlsClientFactory: OwnedTlsClientFactory,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun runTest(): ConnectionTestState =
            try {
                var responseCode: Int
                val latencyMs =
                    measureTimeMillis {
                        val request =
                            Request
                                .Builder()
                                .url(CONNECTIVITY_CHECK_URL)
                                .head()
                                .build()
                        tlsClientFactory
                            .create {
                                connectTimeout(TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                                readTimeout(TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                                callTimeout(TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                                followRedirects(false)
                            }.newCall(request)
                            .execute()
                            .use { response ->
                                responseCode = response.code
                            }
                    }
                if (responseCode in httpSuccessCodeMin..httpSuccessCodeMax) {
                    ConnectionTestState.Success(latencyMs = latencyMs)
                } else {
                    ConnectionTestState.Failed(reason = "HTTP $responseCode")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionTestState.Failed(reason = e.message ?: "Connection failed")
            }

        private companion object {
            const val CONNECTIVITY_CHECK_URL = "https://connectivitycheck.gstatic.com/generate_204"
            const val TIMEOUT_MS = 10_000
        }
    }
