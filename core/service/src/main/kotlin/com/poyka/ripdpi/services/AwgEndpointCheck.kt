package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint
import com.poyka.ripdpi.core.RipDpiWarpAmneziaConfig
import com.poyka.ripdpi.core.RipDpiWarpEndpointProbeBindings
import com.poyka.ripdpi.core.WarpEndpointProbeNativeRequest
import com.poyka.ripdpi.core.WarpEndpointProbeNativeResult
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.serialization.RipDpiContractJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AwgEndpointCheckResult {
    data class Answered(
        val handshakeMillis: Long,
    ) : AwgEndpointCheckResult

    data object NoAnswer : AwgEndpointCheckResult

    data object Unsupported : AwgEndpointCheckResult

    data object EnvironmentChanged : AwgEndpointCheckResult
}

fun interface AwgEndpointCheck {
    suspend fun check(request: AwgActivationRequest): AwgEndpointCheckResult
}

@Singleton
internal class NativeAwgEndpointCheck
    @Inject
    constructor(
        private val bindings: RipDpiWarpEndpointProbeBindings,
        private val protection: ActiveProtectSocketPathProvider,
    ) : AwgEndpointCheck {
        override suspend fun check(request: AwgActivationRequest): AwgEndpointCheckResult {
            val probe = request.toProbeRequest() ?: return AwgEndpointCheckResult.Unsupported
            return withContext(Dispatchers.IO) {
                protection.captureWarpProbe().use { lease ->
                    if (!lease.isCurrent()) return@withContext AwgEndpointCheckResult.EnvironmentChanged
                    val json =
                        RipDpiContractJson.encodeToString(
                            WarpEndpointProbeNativeRequest.serializer(),
                            probe.copy(socketProtection = lease.socketProtection),
                        )
                    val answer = bindings.probeEndpoint(json, lease.controller)
                    when {
                        !lease.isCurrent() -> {
                            AwgEndpointCheckResult.EnvironmentChanged
                        }

                        answer == null -> {
                            AwgEndpointCheckResult.NoAnswer
                        }

                        else -> {
                            AwgEndpointCheckResult.Answered(
                                RipDpiContractJson
                                    .decodeFromString(WarpEndpointProbeNativeResult.serializer(), answer)
                                    .rttMs,
                            )
                        }
                    }
                }
            }
        }
    }

internal fun AwgActivationRequest.toProbeRequest(): WarpEndpointProbeNativeRequest? {
    val ready = carrier == AwgActivationRequest.CARRIER_UDP && runCatching { requireRuntimeReady() }.isSuccess
    if (!ready) return null
    return WarpEndpointProbeNativeRequest(
        endpoint = ResolvedRipDpiWarpEndpoint(host = endpointHost, port = endpointPort, source = "awg_profile"),
        privateKey = privateKey,
        peerPublicKey = peerPublicKey,
        amnezia = obfuscation.toProbeConfig(presharedKey, persistentKeepalive),
        timeoutMs = AwgCheckTimeoutMs,
    )
}

private fun AwgActivationObfuscation.toProbeConfig(
    presharedKey: String,
    persistentKeepalive: Int,
) = RipDpiWarpAmneziaConfig(
    wireProfile = wireProfile,
    enabled = this != AwgActivationObfuscation(),
    jc = jc,
    jmin = jmin,
    jmax = jmax,
    h1 = h1,
    h2 = h2,
    h3 = h3,
    h4 = h4,
    s1 = s1,
    s2 = s2,
    s3 = s3,
    s4 = s4,
    i1 = i1,
    i2 = i2,
    i3 = i3,
    i4 = i4,
    i5 = i5,
    presharedKey = presharedKey,
    persistentKeepalive = persistentKeepalive,
    v3 = v3,
)

private const val AwgCheckTimeoutMs = 5_000L

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AwgEndpointCheckModule {
    @Binds
    abstract fun bindAwgEndpointCheck(check: NativeAwgEndpointCheck): AwgEndpointCheck
}
