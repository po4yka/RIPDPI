package com.poyka.ripdpi.service.awg

import com.poyka.ripdpi.core.ResolvedRipDpiAmneziaWgConfig
import com.poyka.ripdpi.core.RipDpiAmneziaWgCarrierKind
import com.poyka.ripdpi.core.RipDpiAmneziaWgObfuscationConfig
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates the app-reachable [AwgActivationRequest] (a `:core:data:runtime-state`
 * type that crosses the `:app` -> `:core:service` boundary) into the engine-api
 * [ResolvedRipDpiAmneziaWgConfig] the native AmneziaWG runtime consumes.
 *
 * This mirrors [com.poyka.ripdpi.service.warp.WarpRuntimeConfigResolver]: it is the
 * one hop that owns the `Resolved*` type (which is **not** on the `:app` compile
 * classpath because `:core:engine-api` is `implementation`-only on `:core:service`).
 * Unlike the WARP resolver it does not touch credential/endpoint stores -- the
 * standalone profile is self-contained in the request -- so the translation is a
 * pure structural copy plus the loopback SOCKS bind the tunnel listens on.
 */
internal interface AmneziaWgRuntimeConfigResolver {
    fun resolve(request: AwgActivationRequest): ResolvedRipDpiAmneziaWgConfig
}

@Singleton
internal class DefaultAmneziaWgRuntimeConfigResolver
    @Inject
    constructor() : AmneziaWgRuntimeConfigResolver {
        override fun resolve(request: AwgActivationRequest): ResolvedRipDpiAmneziaWgConfig {
            request.requireRuntimeReady()
            val carrier = request.carrier.toCarrierKind()
            return ResolvedRipDpiAmneziaWgConfig(
                enabled = true,
                profileId = request.profileId,
                privateKey = request.privateKey,
                peerPublicKey = request.peerPublicKey,
                presharedKey = request.presharedKey,
                endpointHost = request.endpointHost,
                endpointPort = request.endpointPort,
                interfaceAddressV4 = request.interfaceAddressV4,
                interfaceAddressV6 = request.interfaceAddressV6,
                mtu = request.mtu,
                persistentKeepalive = request.persistentKeepalive,
                amnezia = request.obfuscation.toObfuscationConfig(),
                carrier = carrier,
                carrierWsUrl = request.carrierWsUrl,
                // The tunnel exposes a loopback SOCKS inbound the rest of the data
                // plane dials; never an externally-reachable bind. Loopback is
                // exempt from the VpnService.protect invariant.
                localSocksHost = LoopbackHost,
                localSocksPort = AmneziaWgLocalSocksPort,
            )
        }

        private companion object {
            private const val LoopbackHost = "127.0.0.1"
        }
    }

/**
 * Loopback SOCKS port shared by the AmneziaWG runtime and the proxy that dials it.
 * Keeping the bind and upstream endpoint on one constant prevents a live tunnel
 * from publishing readiness while the proxy points at a different port.
 */
internal const val AmneziaWgLocalSocksPort = 10808

/**
 * Map the activation-request carrier token (`udp`/`ws`, mirroring the Rust
 * `AmneziaWgCarrierKind` serde wire) onto the engine-api enum. An unrecognized
 * token falls back to [RipDpiAmneziaWgCarrierKind.Udp] (today's behavior) rather
 * than failing activation — forward-compatible with an older persisted request.
 */
private fun String.toCarrierKind(): RipDpiAmneziaWgCarrierKind =
    when (this) {
        AwgActivationRequest.CARRIER_WS -> RipDpiAmneziaWgCarrierKind.Ws
        else -> RipDpiAmneziaWgCarrierKind.Udp
    }

/**
 * Structural copy of the activation-request obfuscation knobs into the engine-api
 * wire DTO. Both sides mirror the Rust `AmneziaWgObfuscation` field-for-field, so
 * this stays a plain field map (including the additive `s3`/`s4` and the AWG-2.0
 * `i1`..`i5` special-junk templates).
 */
private fun com.poyka.ripdpi.data.awg.AwgActivationObfuscation.toObfuscationConfig(): RipDpiAmneziaWgObfuscationConfig =
    RipDpiAmneziaWgObfuscationConfig(
        jc = jc,
        jmin = jmin,
        jmax = jmax,
        s1 = s1,
        s2 = s2,
        s3 = s3,
        s4 = s4,
        h1 = h1,
        h2 = h2,
        h3 = h3,
        h4 = h4,
        i1 = i1,
        i2 = i2,
        i3 = i3,
        i4 = i4,
        i5 = i5,
    )

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AmneziaWgRuntimeConfigResolverModule {
    @Binds
    @Singleton
    abstract fun bindAmneziaWgRuntimeConfigResolver(
        resolver: DefaultAmneziaWgRuntimeConfigResolver,
    ): AmneziaWgRuntimeConfigResolver
}
