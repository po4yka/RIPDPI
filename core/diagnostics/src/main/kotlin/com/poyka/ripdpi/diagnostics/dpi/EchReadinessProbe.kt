package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Locale

enum class EchProbeVerdict {
    ECH_OK,
    ECH_REJECTED,
    ECH_NO_CONFIG,
    ECH_LOOKUP_INCONCLUSIVE,
    ECH_UNSUPPORTED_ENDPOINT,
    ECH_UNUSABLE_CONFIG,
    ECH_UNKNOWN_KEY,
    ECH_NETWORK_BLOCK,
}

data class EchProbeResult(
    val target: String,
    val verdict: EchProbeVerdict,
    val httpsRrFetched: Boolean,
    val echConfigBytesB64: String?,
    val tlsLatencyMs: Long?,
    val negotiatedEch: Boolean,
    val errorDetail: String?,
    val httpsLookups: List<HttpsRrLookupAttempt> = emptyList(),
) {
    val hasMeasurement: Boolean
        get() =
            when (verdict) {
                EchProbeVerdict.ECH_OK,
                EchProbeVerdict.ECH_REJECTED,
                EchProbeVerdict.ECH_UNKNOWN_KEY,
                EchProbeVerdict.ECH_NETWORK_BLOCK,
                -> true

                else -> false
            }
}

data class EchTlsHandshakeResult(
    val negotiatedEch: Boolean,
    val latencyMs: Long?,
)

data class EchTlsEndpoint(
    val origin: String,
    val host: String,
    val port: Int,
)

@Serializable
internal enum class EchTlsSetupFailure {
    @SerialName("unusableConfig")
    UNUSABLE_CONFIG,

    @SerialName("endpointDenied")
    ENDPOINT_DENIED,

    @SerialName("unavailable")
    UNAVAILABLE,
}

internal class EchTlsSetupException(
    val failure: EchTlsSetupFailure,
    message: String?,
) : IOException(message)

interface EchTlsHandshake {
    suspend fun connect(
        endpoint: EchTlsEndpoint,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult
}

class EchReadinessProbe(
    private val resolver: HttpsRrResolver = HttpsRrResolver(),
    private val tlsHandshake: EchTlsHandshake = UnsupportedEchTlsHandshake,
    private val concurrency: Int = DefaultConcurrency,
) {
    suspend fun check(target: String): EchProbeResult {
        val lookup = resolver.fetch(target)
        var setupFailure: EchProbeResult? = null
        for (candidate in lookup.candidates.take(MaxBindingAttempts)) {
            val result = checkBinding(target, candidate, lookup.attempts)
            if (result.hasMeasurement) return result
            setupFailure = result
        }
        return setupFailure ?: EchProbeResult(
            target = target,
            verdict =
                when {
                    lookup.hasIncompatibleBinding -> EchProbeVerdict.ECH_UNSUPPORTED_ENDPOINT
                    lookup.configurationAbsent -> EchProbeVerdict.ECH_NO_CONFIG
                    else -> EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE
                },
            httpsRrFetched = lookup.receivedRecords,
            echConfigBytesB64 = null,
            tlsLatencyMs = null,
            negotiatedEch = false,
            errorDetail = null,
            httpsLookups = lookup.attempts,
        )
    }

    private suspend fun checkBinding(
        target: String,
        record: HttpsRrRecord,
        attempts: List<HttpsRrLookupAttempt>,
    ): EchProbeResult {
        val origin = target.removeSuffix(".").lowercase(Locale.ROOT)
        val host = if (record.targetName == ".") requireNotNull(record.ownerName) else record.targetName
        val endpoint = EchTlsEndpoint(origin, host.lowercase(Locale.ROOT), record.port)
        val configBytes = requireNotNull(record.echConfig)
        return try {
            val result = tlsHandshake.connect(endpoint, configBytes)
            EchProbeResult(
                target = target,
                verdict = if (result.negotiatedEch) EchProbeVerdict.ECH_OK else EchProbeVerdict.ECH_REJECTED,
                httpsRrFetched = true,
                echConfigBytesB64 = Base64.getEncoder().encodeToString(configBytes),
                tlsLatencyMs = result.latencyMs,
                negotiatedEch = result.negotiatedEch,
                errorDetail = null,
                httpsLookups = attempts,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            buildFailedEchProbeResult(target, configBytes, attempts, error)
        } catch (error: IllegalArgumentException) {
            buildFailedEchProbeResult(target, configBytes, attempts, error)
        } catch (error: IllegalStateException) {
            buildFailedEchProbeResult(target, configBytes, attempts, error)
        } catch (error: UnsupportedOperationException) {
            buildFailedEchProbeResult(target, configBytes, attempts, error)
        }
    }

    private fun buildFailedEchProbeResult(
        target: String,
        configBytes: ByteArray,
        attempts: List<HttpsRrLookupAttempt>,
        error: Exception,
    ): EchProbeResult {
        val verdict = classifyEchFailure(error)
        return EchProbeResult(
            target = target,
            verdict = verdict,
            httpsRrFetched = true,
            echConfigBytesB64 = Base64.getEncoder().encodeToString(configBytes),
            tlsLatencyMs = null,
            negotiatedEch = false,
            errorDetail = error.message ?: error.javaClass.simpleName,
            httpsLookups = attempts,
        )
    }

    suspend fun checkAll(targets: List<String>): List<EchProbeResult> =
        coroutineScope {
            val permits = Semaphore(concurrency.coerceIn(MinConcurrency, DefaultConcurrency))
            val checks =
                targets.map { target ->
                    async {
                        permits.withPermit {
                            check(target)
                        }
                    }
                }
            checks.awaitAll()
        }

    private fun classifyEchFailure(error: Exception): EchProbeVerdict {
        val message = (error.message ?: "").lowercase()
        return when {
            error is EchTlsSetupException -> {
                when (error.failure) {
                    EchTlsSetupFailure.UNUSABLE_CONFIG -> EchProbeVerdict.ECH_UNUSABLE_CONFIG
                    EchTlsSetupFailure.ENDPOINT_DENIED -> EchProbeVerdict.ECH_UNSUPPORTED_ENDPOINT
                    EchTlsSetupFailure.UNAVAILABLE -> EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE
                }
            }

            error is UnsupportedOperationException -> {
                EchProbeVerdict.ECH_UNSUPPORTED_ENDPOINT
            }

            "ech_required" in message -> {
                EchProbeVerdict.ECH_UNKNOWN_KEY
            }

            "encrypted_client_hello" in message -> {
                EchProbeVerdict.ECH_REJECTED
            }

            error is SocketTimeoutException || "connect" in message || "timeout" in message -> {
                EchProbeVerdict.ECH_NETWORK_BLOCK
            }

            else -> {
                EchProbeVerdict.ECH_REJECTED
            }
        }
    }

    private companion object {
        private const val MaxBindingAttempts = 4
        private const val MinConcurrency = 1
        private const val DefaultConcurrency = 4
    }
}

private object UnsupportedEchTlsHandshake : EchTlsHandshake {
    override suspend fun connect(
        endpoint: EchTlsEndpoint,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult = throw UnsupportedOperationException("ECH TLS handshake bridge is not available")
}
