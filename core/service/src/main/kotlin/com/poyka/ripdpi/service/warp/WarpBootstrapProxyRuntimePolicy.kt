package com.poyka.ripdpi.service.warp

import com.poyka.ripdpi.core.RipDpiHostsConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.BuiltInWarpControlPlaneHosts
import java.net.ServerSocket
import javax.inject.Inject

internal class WarpBootstrapLoopbackPortAllocator
    @Inject
    constructor() {
        fun reserve(): Int = ServerSocket(0).use { it.localPort }
    }

internal class WarpBootstrapProxyRuntimePolicy
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) {
        suspend fun preferencesFor(bootstrapPort: Int): RipDpiProxyUIPreferences {
            val settings = appSettingsRepository.snapshot()
            val bootstrapSettings =
                settings
                    .toBuilder()
                    .setWsTunnelEnabled(false)
                    .setWsTunnelMode("off")
                    .setWsTunnelFakeSni("")
                    .setWsTunnelAllowInsecureSni(false)
                    .setWsTunnelWorkerUrl("")
                    .setWsTunnelWorkerCredentialRef("")
                    .build()
            val basePreferences =
                RipDpiProxyUIPreferences.fromSettings(
                    bootstrapSettings,
                )
            return RipDpiProxyUIPreferences(
                protocols = basePreferences.protocols,
                parserEvasions = basePreferences.parserEvasions,
                adaptiveFallback = basePreferences.adaptiveFallback,
                wsTunnel = basePreferences.wsTunnel,
                listen = basePreferences.listen.copy(ip = LoopbackHost, port = bootstrapPort),
                chains = basePreferences.chains,
                fakePackets = basePreferences.fakePackets,
                quic = basePreferences.quic,
                hosts =
                    RipDpiHostsConfig(
                        mode = RipDpiHostsConfig.Mode.Whitelist,
                        entries = BuiltInWarpControlPlaneHosts.joinToString(separator = "\n"),
                    ),
                relay = RipDpiRelayConfig(enabled = false),
                warp = RipDpiWarpConfig(enabled = false),
                hostAutolearn = basePreferences.hostAutolearn,
                nativeLogLevel = basePreferences.nativeLogLevel,
                runtimeContext = basePreferences.runtimeContext,
                logContext = basePreferences.logContext,
                rootMode = basePreferences.rootMode,
                rootHelperSocketPath = basePreferences.rootHelperSocketPath,
            )
        }

        private companion object {
            private const val LoopbackHost = "127.0.0.1"
        }
    }
