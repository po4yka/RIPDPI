package com.poyka.ripdpi.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val RelayPresetAssetPath = "integrations/relay-presets.json"
private const val RussianMobileRelayPresetId = "ru-mobile-relay"

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
)

data class RelayPresetSuggestion(
    val preset: RelayPresetDefinition,
    val reason: String,
)

internal fun decodeRelayPresetCatalog(json: String): List<RelayPresetDefinition> =
    Json { ignoreUnknownKeys = true }
        .decodeFromString(RelayPresetCatalogPayload.serializer(), json)
        .presets

internal fun suggestRelayPreset(
    snapshot: NativeNetworkSnapshot?,
    presets: List<RelayPresetDefinition>,
): RelayPresetSuggestion? {
    val cellular = snapshot?.cellular ?: return null
    if (snapshot.transport != "cellular") return null
    if (!cellular.operatorCode.startsWith("250")) return null
    val preset = presets.firstOrNull { it.id == RussianMobileRelayPresetId } ?: return null
    return RelayPresetSuggestion(
        preset = preset,
        reason =
            "Russian cellular network detected. Keep domestic destinations direct and use a Russian first hop " +
                "when whitelist pressure degrades foreign relay reachability.",
    )
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

        fun suggestFor(snapshot: NativeNetworkSnapshot?): RelayPresetSuggestion? = suggestRelayPreset(snapshot, presets)
    }
