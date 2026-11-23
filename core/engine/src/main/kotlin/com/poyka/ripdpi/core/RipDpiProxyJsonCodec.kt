package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.codec.AdaptiveSectionCodec
import com.poyka.ripdpi.core.codec.ChainCodec
import com.poyka.ripdpi.core.codec.DestinationRoutingSectionCodec
import com.poyka.ripdpi.core.codec.NativeProxyConfig
import com.poyka.ripdpi.core.codec.NativeProxyConfigValidation
import com.poyka.ripdpi.core.codec.NetworkSectionCodec
import com.poyka.ripdpi.core.codec.PacketBackendWire
import com.poyka.ripdpi.core.codec.PacketCodec
import com.poyka.ripdpi.core.codec.ProxyLogContextCodec
import com.poyka.ripdpi.core.codec.ProxyRuntimeContextCodec
import com.poyka.ripdpi.core.codec.RelaySectionCodec
import com.poyka.ripdpi.core.codec.SessionOverrideCodec
import com.poyka.ripdpi.core.codec.WarpSectionCodec
import com.poyka.ripdpi.core.codec.WsTunnelSectionCodec
import com.poyka.ripdpi.core.codec.decodeEnvironmentKind
import com.poyka.ripdpi.core.codec.insertWireFields
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.serialization.RipDpiNativeProxyJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
        packetBackendRequested: Boolean = false,
        packetBackendSocketPath: String? = null,
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
                listen =
                    NetworkSectionCodec.toNative(preferences.listen).copy(
                        authToken = listenAuthToken ?: preferences.listen.authToken,
                    ),
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
                geoipDbPath = geoipDbPath,
                geositeDbPath = geositeDbPath,
                environmentKind = environmentKind.name,
                runtimeContext = ProxyRuntimeContextCodec.toNative(preferences.runtimeContext),
                logContext = ProxyLogContextCodec.toNative(preferences.logContext),
                sessionOverrides = SessionOverrideCodec.toNative(localListenPortOverride, localAuthToken),
            ),
            PacketBackendWire.proxyFields(packetBackendRequested, packetBackendSocketPath),
        )

    fun encodeUiPreferences(request: NativeProxyCreateRequest): String =
        encodeUiPreferences(
            preferences = request.preferences,
            strategyPreset = request.strategyPreset,
            packetBackendRequested = request.packetBackendRequested,
            packetBackendSocketPath = request.packetBackendSocketPath,
            geoipDbPath = request.geoipDbPath,
            geositeDbPath = request.geositeDbPath,
            listenAuthToken = request.listenAuthToken,
            localListenPortOverride = request.localListenPortOverride,
            localAuthToken = request.localAuthToken,
            environmentKind = request.environmentKind,
        )

    fun decodeUiPreferences(configJson: String): RipDpiProxyUIPreferences? {
        return runCatching {
            val original = json.parseToJsonElement(configJson).jsonObject
            val payload = decode(original) as? NativeProxyConfig.Ui ?: return null
            val packetBackend = PacketBackendWire.readProxyFields(original)
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
                packetBackendRequested = packetBackend.requested,
                packetBackendSocketPath = packetBackend.socketPath,
                geoipDbPath = payload.geoipDbPath,
                geositeDbPath = payload.geositeDbPath,
                environmentKind = decodeEnvironmentKind(payload.environmentKind),
            )
        }.getOrNull()
    }

    fun stripRuntimeContext(configJson: String): String {
        val original = json.parseToJsonElement(configJson).jsonObject
        val payload = decode(original)
        val updates =
            mutableMapOf<String, JsonElement>(
                "runtimeContext" to JsonNull,
                "logContext" to JsonNull,
                "sessionOverrides" to JsonNull,
            )
        if (payload is NativeProxyConfig.Ui) {
            updates["listen"] =
                patchObject(
                    original["listen"]?.jsonObject ?: JsonObject(emptyMap()),
                    mapOf("authToken" to JsonNull),
                )
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
        val original = json.parseToJsonElement(configJson).jsonObject
        require(decode(original) is NativeProxyConfig.Ui) {
            "UDP ASSOCIATE override requires proxy UI preferences"
        }
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
        packetBackendRequested: Boolean = false,
        packetBackendSocketPath: String? = null,
        geoipDbPath: String? = null,
        geositeDbPath: String? = null,
        localListenPortOverride: Int? = null,
        localAuthToken: String? = null,
        environmentKind: com.poyka.ripdpi.data.EnvironmentKind = com.poyka.ripdpi.data.EnvironmentKind.Unknown,
        relayRuntimeSelection: RipDpiRelayConfig? = null,
    ): String {
        val original = json.parseToJsonElement(configJson).jsonObject
        val payload = decode(original)
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
                        buildMap {
                            put("hostAutolearn", hostAutolearn)
                            putAll(
                                PacketBackendWire.proxyFields(
                                    packetBackendRequested,
                                    packetBackendSocketPath ?: PacketBackendWire.readProxyFields(original).socketPath,
                                    includeNullSocketPath = true,
                                ),
                            )
                            put("geoipDbPath", jsonPrimitiveOrNull(geoipDbPath ?: payload.geoipDbPath))
                            put("geositeDbPath", jsonPrimitiveOrNull(geositeDbPath ?: payload.geositeDbPath))
                            put("environmentKind", JsonPrimitive(environmentKind.name))
                            put("runtimeContext", encodeNullable(json, nextRuntimeContext))
                            put("logContext", encodeNullable(json, nextLogContext))
                            put(
                                "sessionOverrides",
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
                                put("upstreamRelay", patchRelayRuntimeSelection(original, selection))
                            }
                        }
                    patchObject(
                        original,
                        updates,
                    )
                }
            }
        return rewritten.toString()
    }

    private fun decode(element: JsonObject): NativeProxyConfig {
        NativeProxyConfigValidation.validateUiPayloadShape(element)
        return json
            .decodeFromJsonElement(NativeProxyConfig.serializer(), element)
            .also(NativeProxyConfigValidation::validateSupportedPayload)
            .also { if (it is NativeProxyConfig.Ui) PacketBackendWire.readProxyFields(element) }
    }

    private fun encode(
        payload: NativeProxyConfig,
        packetBackendFields: Map<String, JsonElement> = emptyMap(),
    ): String {
        NativeProxyConfigValidation.validateSupportedPayload(payload)
        val document = json.encodeToJsonElement(payload).jsonObject
        return insertWireFields(document, packetBackendFields) {
            it == "geoipDbPath" || it == "geositeDbPath" || it == "environmentKind"
        }.toString()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun prepareNativeCreateJson(configJson: String): String {
        if (PacketBackendWire.preserveUnknownProxyFields) return configJson
        // Persisted profiles keep opaque metadata. The standard JNI request
        // only carries the top-level fields of its active wire contract.
        val document = json.parseToJsonElement(configJson).jsonObject
        val descriptor =
            when (decode(document)) {
                is NativeProxyConfig.CommandLine -> NativeProxyConfig.CommandLine.serializer().descriptor
                is NativeProxyConfig.Ui -> NativeProxyConfig.Ui.serializer().descriptor
            }
        val knownKeys = descriptor.elementNames.toSet() + "kind"
        return JsonObject(document.filterKeys { it in knownKeys }).toString()
    }
}

internal fun withDebugProxyLogDefault(configJson: String): String {
    val original =
        try {
            RipDpiNativeProxyJson.parseToJsonElement(configJson) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return configJson
    val updates =
        when (original["kind"]) {
            JsonPrimitive("ui") -> {
                val level = original["nativeLogLevel"]
                if (level == null ||
                    level == JsonNull
                ) {
                    mapOf("nativeLogLevel" to JsonPrimitive("debug"))
                } else {
                    emptyMap()
                }
            }

            JsonPrimitive("command_line") -> {
                (original["args"] as? JsonArray)
                    ?.let { args ->
                        val first = args.firstOrNull() as? JsonPrimitive
                        val programArgCount = if (first != null && !first.content.startsWith('-')) 1 else 0
                        // The CLI parser applies options in order. Explicit verbosity wins.
                        val debugDefault = listOf(JsonPrimitive("--debug"), JsonPrimitive("1"))
                        val updatedArgs = args.take(programArgCount) + debugDefault + args.drop(programArgCount)
                        mapOf("args" to JsonArray(updatedArgs))
                    }.orEmpty()
            }

            else -> {
                emptyMap()
            }
        }
    return if (updates.isEmpty()) configJson else patchObject(original, updates).toString()
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
    val previousRelay = source["upstreamRelay"]?.jsonObject.orEmpty()
    // Absence belongs to the selected profile; do not inherit prior Mieru options.
    return patchObject(
        JsonObject(previousRelay - setOf("mieruHandshakeMode", "mieruTrafficPattern")),
        selectedRelay,
    )
}
