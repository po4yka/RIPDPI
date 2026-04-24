package com.poyka.ripdpi.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val WarpPayloadGenAssetPath = "integrations/amnezia-payloadgen-presets.json"

const val WarpAmneziaPresetRandom = "random"
const val WarpAmneziaPresetQuicImitation = "quic_imitation"
const val WarpAmneziaPresetTlsImitation = "tls_imitation"
const val WarpAmneziaPresetDnsImitation = "dns_imitation"
const val WarpAmneziaPresetOff = "off"
const val WarpAmneziaPresetCustom = "custom"

// Backward-compatible aliases for older persisted values.
const val WarpAmneziaPresetBalanced = WarpAmneziaPresetQuicImitation
const val WarpAmneziaPresetAggressive = WarpAmneziaPresetTlsImitation
private val warpPayloadGenCatalogJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class WarpPayloadGenCatalogPayload(
    val presets: List<WarpPayloadGenPresetDefinition> = emptyList(),
)

@Serializable
data class WarpPayloadGenPresetDefinition(
    val id: String,
    val label: String,
    val jc: Int,
    val jmin: Int,
    val jmax: Int,
    val h1: Long,
    val h2: Long,
    val h3: Long,
    val h4: Long,
    val s1: Int,
    val s2: Int,
    val s3: Int,
    val s4: Int,
) {
    fun toSettings(enabled: Boolean = id != WarpAmneziaPresetRandom): WarpAmneziaSettings =
        WarpAmneziaSettings(
            enabled = enabled,
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
        )
}

data class WarpPayloadGenSuggestion(
    val preset: WarpPayloadGenPresetDefinition,
    val reason: String,
)

internal fun decodeWarpPayloadGenCatalog(json: String): List<WarpPayloadGenPresetDefinition> =
    warpPayloadGenCatalogJson
        .decodeFromString(WarpPayloadGenCatalogPayload.serializer(), json)
        .presets

@Suppress("ReturnCount")
fun suggestWarpPayloadGenPreset(
    snapshot: NativeNetworkSnapshot?,
    presets: List<WarpPayloadGenPresetDefinition>,
): WarpPayloadGenSuggestion? {
    val cellular = snapshot?.cellular
    val isCellular = snapshot?.transport == "cellular" && cellular != null
    if (!isCellular) return null
    val cellularSnapshot = cellular
    val presetId =
        when {
            cellularSnapshot.operatorCode.startsWith("250") -> WarpAmneziaPresetQuicImitation
            cellularSnapshot.generation == "5g" -> WarpAmneziaPresetTlsImitation
            else -> WarpAmneziaPresetDnsImitation
        }
    return presets.firstOrNull { it.id == presetId }?.let { preset ->
        WarpPayloadGenSuggestion(
            preset = preset,
            reason =
                "Operator hints suggest ${preset.label.lowercase()} for this network. " +
                    "The preset is only suggested and is not applied automatically.",
        )
    }
}

fun builtInWarpPayloadGenPresets(): List<WarpPayloadGenPresetDefinition> =
    decodeWarpPayloadGenCatalog(
        """
        {
          "presets": [
            { "id": "random", "label": "Random", "jc": 0, "jmin": 0, "jmax": 0, "h1": 0, "h2": 0, "h3": 0, "h4": 0, "s1": 0, "s2": 0, "s3": 0, "s4": 0 },
            { "id": "quic_imitation", "label": "QUIC imitation", "jc": 3, "jmin": 16, "jmax": 64, "h1": 1, "h2": 2, "h3": 3, "h4": 4, "s1": 8, "s2": 12, "s3": 16, "s4": 20 },
            { "id": "tls_imitation", "label": "TLS imitation", "jc": 2, "jmin": 24, "jmax": 96, "h1": 11, "h2": 12, "h3": 13, "h4": 14, "s1": 10, "s2": 14, "s3": 18, "s4": 22 },
            { "id": "dns_imitation", "label": "DNS imitation", "jc": 1, "jmin": 8, "jmax": 24, "h1": 21, "h2": 22, "h3": 23, "h4": 24, "s1": 4, "s2": 6, "s3": 8, "s4": 10 }
          ]
        }
        """.trimIndent(),
    )

@Singleton
class WarpPayloadGenCatalog
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val presets: List<WarpPayloadGenPresetDefinition> by lazy {
            context.assets.open(WarpPayloadGenAssetPath).bufferedReader().use { reader ->
                decodeWarpPayloadGenCatalog(reader.readText())
            }
        }

        fun all(): List<WarpPayloadGenPresetDefinition> = presets

        fun find(id: String): WarpPayloadGenPresetDefinition? = presets.firstOrNull { it.id == id }

        fun suggestFor(snapshot: NativeNetworkSnapshot?): WarpPayloadGenSuggestion? =
            suggestWarpPayloadGenPreset(snapshot, presets)
    }
