package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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
    ECH_HANDSHAKE_INCONCLUSIVE,
    ECH_UNSUPPORTED_ENDPOINT,
    ECH_UNUSABLE_CONFIG,
    ECH_TIMEOUT,
    ECH_TRANSPORT_ERROR,
    ECH_TLS_ERROR,
    ECH_CERTIFICATE_ERROR,
    ECH_BRIDGE_UNAVAILABLE,
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
    val bindingAttempts: List<EchBindingAttempt> = emptyList(),
) {
    val hasMeasurement: Boolean
        get() = verdict == EchProbeVerdict.ECH_OK || verdict == EchProbeVerdict.ECH_REJECTED
}

data class EchBindingAttempt(
    val source: HttpsRrSource,
    val endpoint: EchTlsEndpoint,
    val verdict: EchProbeVerdict,
    val stage: EchTlsStage? = null,
    val peerAddress: String? = null,
    val latencyMs: Long? = null,
    val retryAttempted: Boolean? = null,
    val errorDetail: String? = null,
)

data class EchTlsHandshakeResult(
    val negotiatedEch: Boolean,
    val latencyMs: Long?,
    val peerAddress: String? = null,
    val retryAttempted: Boolean? = null,
)

data class EchTlsEndpoint(
    val origin: String,
    val host: String,
    val port: Int,
)

@Serializable
enum class EchTlsStage {
    @SerialName("setup")
    SETUP,

    @SerialName("connect")
    CONNECT,

    @SerialName("tls")
    TLS,
}

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
        val attempts = mutableListOf<EchBindingAttempt>()
        for (candidate in lookup.candidates.take(MaxBindingAttempts)) {
            val origin = target.trimEnd('.').lowercase(Locale.ROOT)
            val host = if (candidate.targetName == ".") requireNotNull(candidate.ownerName) else candidate.targetName
            val endpoint = EchTlsEndpoint(origin, host.lowercase(Locale.ROOT), candidate.port)
            val attempt = checkBinding(lookup.candidateSource, endpoint, requireNotNull(candidate.echConfig))
            attempts += attempt
            if (attempt.verdict == EchProbeVerdict.ECH_OK) break
        }

        val verdict = lookup.summarize(attempts)
        val selectedIndex = attempts.indexOfFirst { it.verdict == verdict }
        val selectedAttempt = attempts.getOrNull(selectedIndex)
        return EchProbeResult(
            target = target,
            verdict = verdict,
            httpsRrFetched = lookup.receivedRecords,
            echConfigBytesB64 =
                lookup.candidates
                    .getOrNull(selectedIndex)
                    ?.echConfig
                    ?.let(Base64.getEncoder()::encodeToString),
            tlsLatencyMs = selectedAttempt?.latencyMs,
            negotiatedEch = verdict == EchProbeVerdict.ECH_OK,
            errorDetail = selectedAttempt?.errorDetail,
            httpsLookups = lookup.attempts,
            bindingAttempts = attempts.toList(),
        )
    }

    private suspend fun checkBinding(
        source: HttpsRrSource,
        endpoint: EchTlsEndpoint,
        config: ByteArray,
    ): EchBindingAttempt =
        try {
            val result = withTimeoutOrNull(BindingTimeoutMs) { tlsHandshake.connect(endpoint, config) }
            if (result == null) {
                EchBindingAttempt(source, endpoint, EchProbeVerdict.ECH_TIMEOUT)
            } else {
                EchBindingAttempt(
                    source = source,
                    endpoint = endpoint,
                    verdict = if (result.negotiatedEch) EchProbeVerdict.ECH_OK else EchProbeVerdict.ECH_REJECTED,
                    stage = EchTlsStage.TLS,
                    peerAddress = result.peerAddress,
                    latencyMs = result.latencyMs,
                    retryAttempted = result.retryAttempted,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: EchTlsException) {
            EchBindingAttempt(
                source = source,
                endpoint = endpoint,
                verdict = error.outcome.toVerdict(),
                stage = error.stage,
                peerAddress = error.peerAddress,
                latencyMs = error.latencyMs,
                retryAttempted = error.retryAttempted,
                errorDetail = error.outcome.name,
            )
        } catch (_: SocketTimeoutException) {
            EchBindingAttempt(source, endpoint, EchProbeVerdict.ECH_TIMEOUT)
        } catch (_: IOException) {
            EchBindingAttempt(source, endpoint, EchProbeVerdict.ECH_TRANSPORT_ERROR)
        } catch (_: IllegalArgumentException) {
            EchBindingAttempt(source, endpoint, EchProbeVerdict.ECH_BRIDGE_UNAVAILABLE)
        } catch (_: IllegalStateException) {
            EchBindingAttempt(source, endpoint, EchProbeVerdict.ECH_BRIDGE_UNAVAILABLE)
        } catch (_: UnsupportedOperationException) {
            EchBindingAttempt(source, endpoint, EchProbeVerdict.ECH_BRIDGE_UNAVAILABLE)
        }

    suspend fun checkAll(targets: List<String>): List<EchProbeResult> =
        coroutineScope {
            val permits = Semaphore(concurrency.coerceIn(MinConcurrency, DefaultConcurrency))
            targets
                .map { target -> async { permits.withPermit { check(target) } } }
                .awaitAll()
        }

    private companion object {
        private const val MaxBindingAttempts = 4

        private const val BindingTimeoutMs = 11_000L
        private const val MinConcurrency = 1
        private const val DefaultConcurrency = 4
    }
}

private fun HttpsRrLookup.summarize(attempts: List<EchBindingAttempt>): EchProbeVerdict {
    if (attempts.isEmpty()) {
        return when {
            !configurationAbsent -> EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE
            hasIncompatibleBinding -> EchProbeVerdict.ECH_UNSUPPORTED_ENDPOINT
            else -> EchProbeVerdict.ECH_NO_CONFIG
        }
    }

    val first = attempts.first().verdict
    return when {
        attempts.last().verdict == EchProbeVerdict.ECH_OK -> EchProbeVerdict.ECH_OK
        attempts.size == candidates.size && attempts.all { it.verdict == first } -> first
        else -> EchProbeVerdict.ECH_HANDSHAKE_INCONCLUSIVE
    }
}

private fun EchTlsOutcome.toVerdict(): EchProbeVerdict =
    when (this) {
        EchTlsOutcome.UNUSABLE_CONFIG -> {
            EchProbeVerdict.ECH_UNUSABLE_CONFIG
        }

        EchTlsOutcome.ENDPOINT_DENIED -> {
            EchProbeVerdict.ECH_UNSUPPORTED_ENDPOINT
        }

        EchTlsOutcome.TIMEOUT -> {
            EchProbeVerdict.ECH_TIMEOUT
        }

        EchTlsOutcome.TRANSPORT_ERROR -> {
            EchProbeVerdict.ECH_TRANSPORT_ERROR
        }

        EchTlsOutcome.TLS_ERROR -> {
            EchProbeVerdict.ECH_TLS_ERROR
        }

        EchTlsOutcome.CERTIFICATE_ERROR -> {
            EchProbeVerdict.ECH_CERTIFICATE_ERROR
        }

        EchTlsOutcome.UNAVAILABLE, EchTlsOutcome.ACCEPTED, EchTlsOutcome.REJECTED, EchTlsOutcome.CANCELLED -> {
            EchProbeVerdict.ECH_BRIDGE_UNAVAILABLE
        }
    }

private object UnsupportedEchTlsHandshake : EchTlsHandshake {
    override suspend fun connect(
        endpoint: EchTlsEndpoint,
        echConfig: ByteArray,
    ): EchTlsHandshakeResult = throw UnsupportedOperationException("ECH TLS handshake bridge is not available")
}
