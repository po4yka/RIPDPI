package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.activities.ConnectionTestState
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

@Singleton
class OnboardingConnectionTestRunner
    @Inject
    constructor() {
        suspend fun runTest(): ConnectionTestState =
            try {
                var responseCode: Int
                val latencyMs =
                    measureTimeMillis {
                        val connection =
                            URL(CONNECTIVITY_CHECK_URL).openConnection() as HttpURLConnection
                        connection.requestMethod = "HEAD"
                        connection.connectTimeout = TIMEOUT_MS
                        connection.readTimeout = TIMEOUT_MS
                        connection.instanceFollowRedirects = false
                        try {
                            connection.connect()
                            responseCode = connection.responseCode
                        } finally {
                            connection.disconnect()
                        }
                    }
                if (responseCode in 200..399) {
                    ConnectionTestState.Success(latencyMs = latencyMs)
                } else {
                    ConnectionTestState.Failed(reason = "HTTP $responseCode")
                }
            } catch (e: Exception) {
                ConnectionTestState.Failed(reason = e.message ?: "Connection failed")
            }

        private companion object {
            const val CONNECTIVITY_CHECK_URL = "https://connectivitycheck.gstatic.com/generate_204"
            const val TIMEOUT_MS = 10_000
        }
    }
