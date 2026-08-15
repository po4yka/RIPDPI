package com.poyka.ripdpi.diagnostics.replay

import android.content.Context
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a string-resource key for a (terminalStep, errorKind) tuple
 * via the JSON catalog at `assets/diagnostics/replay_recommendations.json`.
 *
 * The returned string is ALWAYS a string-resource key (e.g.
 * `vpn_replay_rec_rst_after_client_hello`) — localised resolution
 * happens at the UI layer. The catalog never carries
 * user-visible strings, to avoid locale-rot in the data layer.
 *
 * The catalog is loaded lazily on first lookup and cached for the
 * lifetime of the singleton. Asset open is cheap on Android, but the
 * JSON parse is the expensive part — keeping it lazy + cached
 * eliminates the parse cost from the hot replay path.
 */
@Singleton
class ReplayRecommendationEngine internal constructor(
    private val catalogProvider: () -> ReplayRecommendationCatalog,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this({ loadCatalogFromAssets(context) })

    private val catalog: ReplayRecommendationCatalog by lazy { catalogProvider() }

    /**
     * Look up the recommendation key for a failed replay. Returns the
     * catalog's `defaultRecommendationKey` when no rule matches, or
     * when [terminalStep] / [errorKind] is null (cancelled or never-started
     * replays).
     */
    fun recommendationKeyFor(
        terminalStep: ReplayStepKind?,
        errorKind: ReplayErrorKind?,
    ): String {
        if (terminalStep == null || errorKind == null) {
            return catalog.defaultRecommendationKey
        }
        return catalog.rules
            .firstOrNull { it.step == terminalStep && it.errorKind == errorKind }
            ?.recommendationKey
            ?: catalog.defaultRecommendationKey
    }

    companion object {
        internal const val CATALOG_ASSET_PATH = "diagnostics/replay_recommendations.json"

        /** Test-only factory that bypasses the Android asset loader. */
        internal fun forTesting(catalog: ReplayRecommendationCatalog): ReplayRecommendationEngine =
            ReplayRecommendationEngine(catalogProvider = { catalog })

        private fun loadCatalogFromAssets(context: Context): ReplayRecommendationCatalog =
            context.assets.open(CATALOG_ASSET_PATH).bufferedReader().use { reader ->
                ReplayRecommendationCatalogParser.parse(reader.readText())
            }
    }
}

/**
 * Pure JSON->catalog parser, factored out of [ReplayRecommendationEngine]
 * so unit tests can exercise it without an Android [Context]. Production
 * code never calls this directly — it goes through the engine.
 */
internal object ReplayRecommendationCatalogParser {
    private val JSON = RipDpiJson

    fun parse(json: String): ReplayRecommendationCatalog =
        JSON.decodeFromString(ReplayRecommendationCatalog.serializer(), json)
}

@Serializable
internal data class ReplayRecommendationCatalog(
    val version: Int,
    val rules: List<ReplayRecommendationRule>,
    val defaultRecommendationKey: String,
)

@Serializable
internal data class ReplayRecommendationRule(
    val step: ReplayStepKind,
    val errorKind: ReplayErrorKind,
    val recommendationKey: String,
)
