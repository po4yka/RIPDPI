package com.poyka.ripdpi.core.codec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rejects legacy proxy-config payloads that predate the grouped UI config shape.
 *
 * Used by [RipDpiProxyJsonCodec] to guard both the raw-JSON shape (before
 * deserialization) and the decoded [NativeProxyConfig] (before encode/decode
 * returns control to callers).
 */
internal object NativeProxyConfigValidation {
    private val groupedUiKeys =
        setOf(
            "listen",
            "protocols",
            "chains",
            "fakePackets",
            "parserEvasions",
            "adaptiveFallback",
            "quic",
            "hosts",
            "upstreamRelay",
            "warp",
            "hostAutolearn",
            "wsTunnel",
            "destinationRouting",
        )
    private val legacyFlatUiKeys =
        setOf(
            "ip",
            "port",
            "maxConnections",
            "bufferSize",
            "tcpFastOpen",
            "defaultTtl",
            "customTtl",
            "noDomain",
            "desyncHttp",
            "desyncHttps",
            "desyncUdp",
            "desyncMethod",
            "splitMarker",
            "tcpChainSteps",
            "groupActivationFilter",
            "splitPosition",
            "splitAtHost",
            "fakeTtl",
            "adaptiveFakeTtlEnabled",
            "adaptiveFakeTtlDelta",
            "adaptiveFakeTtlMin",
            "adaptiveFakeTtlMax",
            "adaptiveFakeTtlFallback",
            "fakeSni",
            "httpFakeProfile",
            "fakeTlsUseOriginal",
            "fakeTlsRandomize",
            "fakeTlsDupSessionId",
            "fakeTlsPadEncap",
            "fakeTlsSize",
            "fakeTlsSniMode",
            "tlsFakeProfile",
            "oobChar",
            "hostMixedCase",
            "domainMixedCase",
            "hostRemoveSpaces",
            "httpMethodEol",
            "httpMethodSpace",
            "httpUnixEol",
            "httpHostPad",
            "tlsRecordSplit",
            "tlsRecordSplitMarker",
            "tlsRecordSplitPosition",
            "tlsRecordSplitAtSni",
            "hostsMode",
            "udpFakeCount",
            "udpChainSteps",
            "udpFakeProfile",
            "dropSack",
            "fakeOffsetMarker",
            "fakeOffset",
            "quicInitialMode",
            "quicSupportV1",
            "quicSupportV2",
            "quicFakeProfile",
            "quicFakeHost",
            "hostAutolearnEnabled",
            "hostAutolearnPenaltyTtlSecs",
            "hostAutolearnPenaltyTtlHours",
            "hostAutolearnMaxHosts",
            "hostAutolearnStorePath",
            "networkScopeKey",
            "adaptiveFallbackEnabled",
            "adaptiveFallbackTorst",
            "adaptiveFallbackTlsErr",
            "adaptiveFallbackHttpRedirect",
            "adaptiveFallbackConnectFailure",
            "adaptiveFallbackAutoSort",
            "adaptiveFallbackCacheTtlSeconds",
            "adaptiveFallbackCachePrefixV4",
        )
    private const val LegacyCommandLineProgram = "cia" + "dpi"
    private const val LegacyStrategyPreset = "bye" + "dpi_default"

    fun validateUiPayloadShape(element: JsonElement) {
        val payload = element as? JsonObject ?: return
        if (payload["kind"]?.jsonPrimitive?.contentOrNull != "ui") {
            return
        }
        require(payload.keys.none(legacyFlatUiKeys::contains)) {
            "Legacy flat UI config JSON is not supported"
        }
        require(payload.keys.any(groupedUiKeys::contains)) {
            "Grouped UI config JSON must include at least one nested section"
        }
        payload["destinationRouting"]?.let(::validateDestinationRoutingShape)
    }

    private fun validateDestinationRoutingShape(element: JsonElement) {
        val policy = element.requireObject("destinationRouting")
        policy.requireOnlyKeys("destinationRouting", destinationRoutingKeys)
        val rules = policy["rules"]?.requireArray("destinationRouting.rules") ?: return
        rules.forEachIndexed { index, ruleElement ->
            val rulePath = "destinationRouting.rules[$index]"
            val rule = ruleElement.requireObject(rulePath)
            rule.requireOnlyKeys(rulePath, destinationRuleKeys)
            validateMatcherArray(rule, "domains", rulePath, domainMatcherKeys)
            validateMatcherArray(rule, "ipRanges", rulePath, ipMatcherKeys)
            validateMatcherArray(rule, "destinationPorts", rulePath, portRangeKeys)
        }
    }

    private fun validateMatcherArray(
        rule: JsonObject,
        key: String,
        rulePath: String,
        allowedKeys: Set<String>,
    ) {
        val values = rule[key]?.requireArray("$rulePath.$key") ?: return
        values.forEachIndexed { index, matcherElement ->
            val matcherPath = "$rulePath.$key[$index]"
            matcherElement.requireObject(matcherPath).requireOnlyKeys(matcherPath, allowedKeys)
        }
    }

    private fun JsonElement.requireObject(path: String): JsonObject =
        requireNotNull(this as? JsonObject) { "$path must be an object" }

    private fun JsonElement.requireArray(path: String): JsonArray =
        requireNotNull(this as? JsonArray) { "$path must be an array" }

    private fun JsonObject.requireOnlyKeys(
        path: String,
        allowedKeys: Set<String>,
    ) {
        val unknownKeys = keys - allowedKeys
        require(unknownKeys.isEmpty()) { "$path contains unknown fields: ${unknownKeys.sorted()}" }
    }

    private val destinationRoutingKeys = setOf("rules", "defaultAction", "canonicalDigest")
    private val destinationRuleKeys = setOf("action", "network", "domains", "ipRanges", "destinationPorts")
    private val domainMatcherKeys = setOf("kind", "value")
    private val ipMatcherKeys = setOf("kind", "value")
    private val portRangeKeys = setOf("start", "endInclusive")

    fun validateSupportedPayload(payload: NativeProxyConfig) {
        val schemaVersion =
            when (payload) {
                is NativeProxyConfig.CommandLine -> payload.schemaVersion
                is NativeProxyConfig.Ui -> payload.schemaVersion
            }
        require(schemaVersion == NativeProxyConfigSchemaVersion) {
            "Unsupported native proxy config schema version: $schemaVersion"
        }
        when (payload) {
            is NativeProxyConfig.CommandLine -> {
                require(payload.args.firstOrNull() != LegacyCommandLineProgram) {
                    "Legacy command-line executable alias is not supported"
                }
                validateDestinationRouting(payload.destinationRouting)
            }

            is NativeProxyConfig.Ui -> {
                require(payload.strategyPreset != LegacyStrategyPreset) {
                    "Legacy strategy preset alias is not supported"
                }
                validateDestinationRouting(payload.destinationRouting)
            }
        }
    }

    private fun validateDestinationRouting(policy: NativeDestinationRoutingConfig) {
        DestinationRoutingWireContract.validate(policy)
    }
}
