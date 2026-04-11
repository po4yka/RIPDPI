package com.poyka.ripdpi.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val RelayPresetAssetPath = "integrations/relay-presets.json"
private const val RussianMobileRelayPresetId = "ru-mobile-relay"
private const val RussianMobileTuicPresetId = "ru-mobile-tuic"
private const val RussianMobileShadowTlsPresetId = "ru-mobile-shadowtls"
private const val RussianMobileNaiveProxyPresetId = "ru-mobile-naiveproxy"

@Serializable
internal data class RelayPresetCatalogPayload(
    val presets: List<RelayPresetDefinition> = emptyList(),
)

@Serializable
data class RelayPresetDefinition(
    val id: String,
    val title: String,
    val entryCountry: String = "",
    val exitCountry: String = "",
    val antiCorrelationSuggested: Boolean = false,
    val routeMode: String = "",
    val relayKind: String = RelayKindChainRelay,
    val chainEntryProfileId: String = "",
    val chainExitProfileId: String = "",
    val shadowTlsInnerProfileId: String = "",
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val naivePath: String = "",
    val udpEnabled: Boolean = false,
    val requiresQuic: Boolean = false,
    val requiresUdp: Boolean = false,
    val requiresShadowTlsCamouflage: Boolean = false,
    val requiresNaiveHttpsProxy: Boolean = false,
    val requiresMultiplexReusable: Boolean = false,
)

data class RelayPresetSuggestion(
    val preset: RelayPresetDefinition,
    val reason: String,
)

internal data class RelayCapabilitySummary(
    val quicUsable: Boolean? = null,
    val udpUsable: Boolean? = null,
    val multiplexReusable: Boolean? = null,
    val shadowTlsCamouflageAccepted: Boolean? = null,
    val naiveHttpsProxyAccepted: Boolean? = null,
    val fallbackRequired: Boolean? = null,
    val repeatedHandshakeFailureClass: String? = null,
)

internal fun decodeRelayPresetCatalog(json: String): List<RelayPresetDefinition> =
    Json { ignoreUnknownKeys = true }
        .decodeFromString(RelayPresetCatalogPayload.serializer(), json)
        .presets

internal fun suggestRelayPreset(
    snapshot: NativeNetworkSnapshot?,
    presets: List<RelayPresetDefinition>,
    capabilityRecords: List<ServerCapabilityRecord> = emptyList(),
): RelayPresetSuggestion? {
    val cellular = snapshot?.cellular ?: return null
    if (snapshot.transport != "cellular") return null
    if (!cellular.operatorCode.startsWith("250")) return null
    val summary = summarizeRelayCapabilities(capabilityRecords)
    val preset =
        presets
            .asSequence()
            .filter { it.routeMode == "direct_for_domestic" }
            .filter { candidate ->
                candidate.supportedBy(summary)
            }.maxByOrNull { candidate ->
                candidate.preferenceScore(summary)
            } ?: presets.firstOrNull { it.id == RussianMobileRelayPresetId }
            ?: return null
    return RelayPresetSuggestion(
        preset = preset,
        reason = preset.suggestionReason(summary),
    )
}

internal fun summarizeRelayCapabilities(records: List<ServerCapabilityRecord>): RelayCapabilitySummary =
    RelayCapabilitySummary(
        quicUsable = records.reduceCapabilityFlag(ServerCapabilityRecord::quicUsable),
        udpUsable = records.reduceCapabilityFlag(ServerCapabilityRecord::udpUsable),
        multiplexReusable = records.reduceCapabilityFlag(ServerCapabilityRecord::multiplexReusable),
        shadowTlsCamouflageAccepted =
            records.reduceCapabilityFlag(ServerCapabilityRecord::shadowTlsCamouflageAccepted),
        naiveHttpsProxyAccepted =
            records.reduceCapabilityFlag(ServerCapabilityRecord::naiveHttpsProxyAccepted),
        fallbackRequired = records.reduceCapabilityFlag(ServerCapabilityRecord::fallbackRequired),
        repeatedHandshakeFailureClass =
            records
                .mapNotNull(ServerCapabilityRecord::repeatedHandshakeFailureClass)
                .firstOrNull { it.isNotBlank() },
    )

private fun RelayPresetDefinition.supportedBy(summary: RelayCapabilitySummary): Boolean {
    if (requiresQuic && summary.quicUsable == false) return false
    if (requiresUdp && summary.udpUsable == false) return false
    if (requiresShadowTlsCamouflage && summary.shadowTlsCamouflageAccepted == false) return false
    if (requiresNaiveHttpsProxy && summary.naiveHttpsProxyAccepted == false) return false
    if (requiresMultiplexReusable && summary.multiplexReusable == false) return false
    return true
}

private fun RelayPresetDefinition.preferenceScore(summary: RelayCapabilitySummary): Int {
    var score = 0
    if (requiresQuic && summary.quicUsable == true) score += 30
    if (requiresUdp && summary.udpUsable == true) score += 20
    if (requiresShadowTlsCamouflage && summary.shadowTlsCamouflageAccepted == true) score += 25
    if (requiresNaiveHttpsProxy && summary.naiveHttpsProxyAccepted == true) score += 25
    if (requiresMultiplexReusable && summary.multiplexReusable == true) score += 10
    if (summary.fallbackRequired == true && relayKind == RelayKindNaiveProxy) score += 8
    if (summary.quicUsable == false && relayKind == RelayKindNaiveProxy) score += 8
    if (summary.quicUsable == false && relayKind == RelayKindChainRelay) score += 4
    score +=
        when (id) {
            RussianMobileShadowTlsPresetId -> if (summary.shadowTlsCamouflageAccepted == true) 60 else 5
            RussianMobileTuicPresetId -> if (summary.quicUsable == true && summary.udpUsable != false) 55 else 4
            RussianMobileNaiveProxyPresetId -> if (summary.naiveHttpsProxyAccepted == true) 50 else 3
            RussianMobileRelayPresetId -> 2
            else -> 1
        }
    return score
}

private fun RelayPresetDefinition.suggestionReason(summary: RelayCapabilitySummary): String =
    when (relayKind) {
        RelayKindShadowTlsV3 -> {
            if (summary.shadowTlsCamouflageAccepted == true) {
                "Saved capability evidence for this network shows ShadowTLS camouflage is accepted. " +
                    "Use the ShadowTLS preset to keep domestic traffic direct while making the outer relay hop blend in."
            } else {
                defaultRussianCellularReason()
            }
        }

        RelayKindTuicV5 -> {
            if (summary.quicUsable == true && summary.udpUsable != false) {
                "Saved capability evidence for this network shows QUIC and UDP relay paths are usable. " +
                    "Use the TUIC preset for the lowest-latency foreign relay path while keeping domestic traffic direct."
            } else {
                defaultRussianCellularReason()
            }
        }

        RelayKindNaiveProxy -> {
            if (summary.naiveHttpsProxyAccepted == true || summary.quicUsable == false) {
                "Saved capability evidence for this network shows HTTPS proxying is the safer fallback than QUIC. " +
                    "Use the NaiveProxy preset before foreign relay reachability collapses."
            } else {
                defaultRussianCellularReason()
            }
        }

        else -> {
            defaultRussianCellularReason()
        }
    }

private fun defaultRussianCellularReason(): String =
    "Russian cellular network detected. Keep domestic destinations direct and use a Russian first hop " +
        "when whitelist pressure degrades foreign relay reachability."

private fun List<ServerCapabilityRecord>.reduceCapabilityFlag(
    selector: (ServerCapabilityRecord) -> Boolean?,
): Boolean? =
    when {
        any { selector(it) == true } -> true
        any { selector(it) == false } -> false
        else -> null
    }

@Singleton
class RelayPresetCatalog
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val presets: List<RelayPresetDefinition> by lazy {
            context.assets.open(RelayPresetAssetPath).bufferedReader().use { reader ->
                decodeRelayPresetCatalog(reader.readText())
            }
        }

        fun all(): List<RelayPresetDefinition> = presets

        fun find(id: String): RelayPresetDefinition? = presets.firstOrNull { it.id == id }

        fun suggestFor(
            snapshot: NativeNetworkSnapshot?,
            capabilityRecords: List<ServerCapabilityRecord> = emptyList(),
        ): RelayPresetSuggestion? = suggestRelayPreset(snapshot, presets, capabilityRecords)
    }
