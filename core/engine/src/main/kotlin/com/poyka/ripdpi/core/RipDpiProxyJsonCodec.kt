package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.codec.AdaptiveSectionCodec
import com.poyka.ripdpi.core.codec.ChainCodec
import com.poyka.ripdpi.core.codec.NativeProxyConfig
import com.poyka.ripdpi.core.codec.NativeProxyConfigValidation
import com.poyka.ripdpi.core.codec.NetworkSectionCodec
import com.poyka.ripdpi.core.codec.PacketCodec
import com.poyka.ripdpi.core.codec.ProxyLogContextCodec
import com.poyka.ripdpi.core.codec.ProxyRuntimeContextCodec
import com.poyka.ripdpi.core.codec.RelaySectionCodec
import com.poyka.ripdpi.core.codec.SessionOverrideCodec
import com.poyka.ripdpi.core.codec.WarpSectionCodec
import com.poyka.ripdpi.core.codec.WsTunnelSectionCodec
import com.poyka.ripdpi.core.codec.decodeEnvironmentKind
import kotlinx.serialization.json.Json

internal object RipDpiProxyJsonCodec {
    private val json =
        Json {
            classDiscriminator = "kind"
            encodeDefaults = true
        }

    fun encodeCommandLinePreferences(
        args: List<String>,
        hostAutolearnStorePath: String?,
        runtimeContext: RipDpiRuntimeContext?,
        logContext: RipDpiLogContext?,
        localListenPortOverride: Int? = null,
        localAuthToken: String? = null,
    ): String =
        encode(
            NativeProxyConfig.CommandLine(
                args = args,
                hostAutolearnStorePath = hostAutolearnStorePath,
                runtimeContext = ProxyRuntimeContextCodec.toNative(runtimeContext),
                logContext = ProxyLogContextCodec.toNative(logContext),
                sessionOverrides = SessionOverrideCodec.toNative(localListenPortOverride, localAuthToken),
            ),
        )

    fun encodeUiPreferences(
        preferences: RipDpiProxyUIPreferences,
        strategyPreset: String? = null,
        rootMode: Boolean = false,
        rootHelperSocketPath: String? = null,
        geoipDbPath: String? = null,
        geositeDbPath: String? = null,
        listenAuthToken: String? = null,
        localListenPortOverride: Int? = null,
        localAuthToken: String? = null,
        environmentKind: com.poyka.ripdpi.data.EnvironmentKind = com.poyka.ripdpi.data.EnvironmentKind.Unknown,
    ): String =
        encode(
            NativeProxyConfig.Ui(
                strategyPreset = strategyPreset,
                listen = NetworkSectionCodec.toNative(preferences.listen).copy(authToken = listenAuthToken),
                protocols = NetworkSectionCodec.toNative(preferences.protocols),
                chains = ChainCodec.toNative(preferences.chains),
                fakePackets = PacketCodec.toNative(preferences.fakePackets),
                parserEvasions = PacketCodec.toNative(preferences.parserEvasions),
                adaptiveFallback = AdaptiveSectionCodec.toNative(preferences.adaptiveFallback),
                quic = NetworkSectionCodec.toNative(preferences.quic),
                hosts = NetworkSectionCodec.toNative(preferences.hosts),
                upstreamRelay = RelaySectionCodec.toNative(preferences.relay),
                warp = WarpSectionCodec.toNative(preferences.warp),
                hostAutolearn = NetworkSectionCodec.toNative(preferences.hostAutolearn),
                wsTunnel = WsTunnelSectionCodec.toNative(preferences.wsTunnel),
                nativeLogLevel = preferences.nativeLogLevel,
                rootMode = rootMode,
                rootHelperSocketPath = rootHelperSocketPath,
                geoipDbPath = geoipDbPath,
                geositeDbPath = geositeDbPath,
                environmentKind = environmentKind.name,
                runtimeContext = ProxyRuntimeContextCodec.toNative(preferences.runtimeContext),
                logContext = ProxyLogContextCodec.toNative(preferences.logContext),
                sessionOverrides = SessionOverrideCodec.toNative(localListenPortOverride, localAuthToken),
            ),
        )

    fun decodeUiPreferences(configJson: String): RipDpiProxyUIPreferences? {
        val payload = decodeOrNull(configJson) as? NativeProxyConfig.Ui ?: return null
        return runCatching {
            RipDpiProxyUIPreferences(
                listen = NetworkSectionCodec.toModel(payload.listen),
                protocols = NetworkSectionCodec.toModel(payload.protocols),
                chains = ChainCodec.toModel(payload.chains),
                fakePackets = PacketCodec.toModel(payload.fakePackets),
                parserEvasions = PacketCodec.toModel(payload.parserEvasions),
                adaptiveFallback = AdaptiveSectionCodec.toModel(payload.adaptiveFallback),
                quic = NetworkSectionCodec.toModel(payload.quic),
                hosts = NetworkSectionCodec.toModel(payload.hosts),
                relay = RelaySectionCodec.toModel(payload.upstreamRelay),
                warp = WarpSectionCodec.toModel(payload.warp),
                hostAutolearn = NetworkSectionCodec.toModel(payload.hostAutolearn),
                wsTunnel = WsTunnelSectionCodec.toModel(payload.wsTunnel),
                nativeLogLevel = payload.nativeLogLevel,
                runtimeContext = ProxyRuntimeContextCodec.toModel(payload.runtimeContext),
                logContext = ProxyLogContextCodec.toModel(payload.logContext),
                rootMode = payload.rootMode,
                rootHelperSocketPath = payload.rootHelperSocketPath,
                geoipDbPath = payload.geoipDbPath,
                geositeDbPath = payload.geositeDbPath,
                environmentKind = decodeEnvironmentKind(payload.environmentKind),
            )
        }.getOrNull()
    }

    fun stripRuntimeContext(configJson: String): String =
        when (val payload = decode(configJson)) {
            is NativeProxyConfig.CommandLine -> encode(payload.copy(runtimeContext = null, logContext = null))
            is NativeProxyConfig.Ui -> encode(payload.copy(runtimeContext = null, logContext = null))
        }

    fun rewriteJson(
        configJson: String,
        hostAutolearnStorePath: String?,
        networkScopeKey: String?,
        runtimeContext: RipDpiRuntimeContext?,
        logContext: RipDpiLogContext?,
        rootMode: Boolean = false,
        rootHelperSocketPath: String? = null,
        geoipDbPath: String? = null,
        geositeDbPath: String? = null,
        localListenPortOverride: Int? = null,
        localAuthToken: String? = null,
        environmentKind: com.poyka.ripdpi.data.EnvironmentKind = com.poyka.ripdpi.data.EnvironmentKind.Unknown,
    ): String =
        when (val payload = decode(configJson)) {
            is NativeProxyConfig.CommandLine -> {
                encode(
                    payload.copy(
                        runtimeContext = ProxyRuntimeContextCodec.toNative(runtimeContext) ?: payload.runtimeContext,
                        logContext = ProxyLogContextCodec.toNative(logContext) ?: payload.logContext,
                        sessionOverrides =
                            SessionOverrideCodec.merge(
                                existing = payload.sessionOverrides,
                                listenPortOverride = localListenPortOverride,
                                authToken = localAuthToken,
                            ),
                    ),
                )
            }

            is NativeProxyConfig.Ui -> {
                val preferences =
                    requireNotNull(decodeUiPreferences(configJson)) {
                        "Unable to decode proxy UI preferences"
                    }.withSessionOverrides(
                        hostAutolearnStorePath = hostAutolearnStorePath ?: payload.hostAutolearn.storePath,
                        networkScopeKey = networkScopeKey ?: payload.hostAutolearn.networkScopeKey,
                        runtimeContext = runtimeContext ?: ProxyRuntimeContextCodec.toModel(payload.runtimeContext),
                        logContext = logContext ?: ProxyLogContextCodec.toModel(payload.logContext),
                    )
                encodeUiPreferences(
                    preferences,
                    strategyPreset = payload.strategyPreset,
                    rootMode = rootMode,
                    rootHelperSocketPath = rootHelperSocketPath ?: payload.rootHelperSocketPath,
                    geoipDbPath = geoipDbPath ?: payload.geoipDbPath,
                    geositeDbPath = geositeDbPath ?: payload.geositeDbPath,
                    listenAuthToken = payload.listen.authToken,
                    localListenPortOverride = localListenPortOverride ?: payload.sessionOverrides?.listenPortOverride,
                    localAuthToken = localAuthToken ?: payload.sessionOverrides?.authToken,
                    environmentKind = environmentKind,
                )
            }
        }

    private fun decode(configJson: String): NativeProxyConfig {
        val element = json.parseToJsonElement(configJson)
        NativeProxyConfigValidation.validateUiPayloadShape(element)
        return json
            .decodeFromString(NativeProxyConfig.serializer(), configJson)
            .also(NativeProxyConfigValidation::validateSupportedPayload)
    }

    private fun decodeOrNull(configJson: String): NativeProxyConfig? = runCatching { decode(configJson) }.getOrNull()

    private fun encode(payload: NativeProxyConfig): String =
        payload
            .also(NativeProxyConfigValidation::validateSupportedPayload)
            .let(json::encodeToString)
}
