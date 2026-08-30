package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.codec.AdaptiveSectionCodec
import com.poyka.ripdpi.core.codec.ChainCodec
import com.poyka.ripdpi.core.codec.DestinationRoutingSectionCodec
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
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.serialization.RipDpiNativeProxyJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal object RipDpiProxyJsonCodec {
    private val json =
        RipDpiNativeProxyJson

    fun encodeCommandLinePreferences(
        args: List<String>,
        hostAutolearnStorePath: String?,
        destinationRouting: DestinationRoutingPolicy = DestinationRoutingPolicy(canonicalDigest = ""),
        geoipDbPath: String? = null,
        geositeDbPath: String? = null,
        runtimeContext: RipDpiRuntimeContext?,
        logContext: RipDpiLogContext?,
        localListenPortOverride: Int? = null,
        localAuthToken: String? = null,
    ): String =
        encode(
            NativeProxyConfig.CommandLine(
                args = args,
                hostAutolearnStorePath = hostAutolearnStorePath,
                destinationRouting = DestinationRoutingSectionCodec.toNative(destinationRouting),
                geoipDbPath = geoipDbPath,
                geositeDbPath = geositeDbPath,
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
                destinationRouting = DestinationRoutingSectionCodec.toNative(preferences.destinationRouting),
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

    fun encodeUiPreferences(request: NativeProxyCreateRequest): String =
        encodeUiPreferences(
            preferences = request.preferences,
            strategyPreset = request.strategyPreset,
            rootMode = request.rootMode,
            rootHelperSocketPath = request.rootHelperSocketPath,
            geoipDbPath = request.geoipDbPath,
            geositeDbPath = request.geositeDbPath,
            listenAuthToken = request.listenAuthToken,
            localListenPortOverride = request.localListenPortOverride,
            localAuthToken = request.localAuthToken,
            environmentKind = request.environmentKind,
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
                destinationRouting = DestinationRoutingSectionCodec.toModel(payload.destinationRouting),
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

    fun stripRuntimeContext(configJson: String): String {
        val payload = decode(configJson)
        val original = json.parseToJsonElement(configJson).jsonObject
        val updates =
            mutableMapOf<String, JsonElement>(
                "runtimeContext" to JsonNull,
                "logContext" to JsonNull,
                "sessionOverrides" to JsonNull,
            )
        if (payload is NativeProxyConfig.Ui) {
            updates["hostAutolearn"] =
                patchObject(
                    original["hostAutolearn"]?.jsonObject ?: JsonObject(emptyMap()),
                    mapOf("storePath" to JsonNull, "networkScopeKey" to JsonNull),
                )
            updates["wsTunnel"] =
                patchObject(
                    original["wsTunnel"]?.jsonObject ?: JsonObject(emptyMap()),
                    mapOf("cloudflareWorkerBearer" to JsonNull),
                )
        }
        return patchObject(original, updates).toString()
    }

    fun rewriteUdpAssociateEnabled(
        configJson: String,
        enabled: Boolean,
    ): String {
        require(decode(configJson) is NativeProxyConfig.Ui) {
            "UDP ASSOCIATE override requires proxy UI preferences"
        }
        val original = json.parseToJsonElement(configJson).jsonObject
        val protocols =
            patchObject(
                original["protocols"]?.jsonObject ?: JsonObject(emptyMap()),
                mapOf("udpAssociateEnabled" to JsonPrimitive(enabled)),
            )
        return patchObject(original, mapOf("protocols" to protocols)).toString()
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
        relayRuntimeSelection: RipDpiRelayConfig? = null,
    ): String {
        val payload = decode(configJson)
        val original = json.parseToJsonElement(configJson).jsonObject
        val rewritten =
            when (payload) {
                is NativeProxyConfig.CommandLine -> {
                    require(relayRuntimeSelection == null) {
                        "Relay runtime selection requires proxy UI preferences"
                    }
                    val nextRuntimeContext =
                        ProxyRuntimeContextCodec.toNative(runtimeContext) ?: payload.runtimeContext
                    val nextLogContext = ProxyLogContextCodec.toNative(logContext) ?: payload.logContext
                    patchObject(
                        original,
                        mapOf(
                            "runtimeContext" to encodeNullable(json, nextRuntimeContext),
                            "geoipDbPath" to jsonPrimitiveOrNull(geoipDbPath ?: payload.geoipDbPath),
                            "geositeDbPath" to jsonPrimitiveOrNull(geositeDbPath ?: payload.geositeDbPath),
                            "logContext" to encodeNullable(json, nextLogContext),
                            "sessionOverrides" to
                                encodeNullable(
                                    json,
                                    SessionOverrideCodec.merge(
                                        existing = payload.sessionOverrides,
                                        listenPortOverride = localListenPortOverride,
                                        authToken = localAuthToken,
                                    ),
                                ),
                        ),
                    )
                }

                is NativeProxyConfig.Ui -> {
                    requireNotNull(decodeUiPreferences(configJson)) { "Unable to decode proxy UI preferences" }
                    val hostAutolearn =
                        patchObject(
                            original["hostAutolearn"]?.jsonObject ?: JsonObject(emptyMap()),
                            mapOf(
                                "storePath" to
                                    jsonPrimitiveOrNull(hostAutolearnStorePath ?: payload.hostAutolearn.storePath),
                                "networkScopeKey" to
                                    jsonPrimitiveOrNull(networkScopeKey ?: payload.hostAutolearn.networkScopeKey),
                            ),
                        )
                    val nextRuntimeContext =
                        ProxyRuntimeContextCodec.toNative(runtimeContext) ?: payload.runtimeContext
                    val nextLogContext = ProxyLogContextCodec.toNative(logContext) ?: payload.logContext
                    val updates =
                        mutableMapOf<String, JsonElement>(
                            "hostAutolearn" to hostAutolearn,
                            "rootMode" to JsonPrimitive(rootMode),
                            "rootHelperSocketPath" to
                                jsonPrimitiveOrNull(rootHelperSocketPath ?: payload.rootHelperSocketPath),
                            "geoipDbPath" to jsonPrimitiveOrNull(geoipDbPath ?: payload.geoipDbPath),
                            "geositeDbPath" to
                                jsonPrimitiveOrNull(geositeDbPath ?: payload.geositeDbPath),
                            "environmentKind" to JsonPrimitive(environmentKind.name),
                            "runtimeContext" to encodeNullable(json, nextRuntimeContext),
                            "logContext" to encodeNullable(json, nextLogContext),
                            "sessionOverrides" to
                                encodeNullable(
                                    json,
                                    SessionOverrideCodec.merge(
                                        existing = payload.sessionOverrides,
                                        listenPortOverride = localListenPortOverride,
                                        authToken = localAuthToken,
                                    ),
                                ),
                        )
                    relayRuntimeSelection?.let { selection ->
                        updates["upstreamRelay"] = patchRelayRuntimeSelection(original, selection)
                    }
                    patchObject(
                        original,
                        updates,
                    )
                }
            }
        return rewritten.toString()
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

private inline fun <reified T> encodeNullable(
    json: Json,
    value: T?,
): JsonElement = value?.let(json::encodeToJsonElement) ?: JsonNull

private fun jsonPrimitiveOrNull(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

private fun patchObject(
    source: JsonObject,
    updates: Map<String, JsonElement>,
): JsonObject = JsonObject(source.toMutableMap().apply { putAll(updates) })

private fun patchRelayRuntimeSelection(
    source: JsonObject,
    selection: RipDpiRelayConfig,
): JsonObject {
    val selectedRelay =
        RipDpiNativeProxyJson
            .encodeToJsonElement(RelaySectionCodec.toNative(selection))
            .jsonObject
    return patchObject(
        source["upstreamRelay"]?.jsonObject ?: JsonObject(emptyMap()),
        selectedRelay,
    )
}
