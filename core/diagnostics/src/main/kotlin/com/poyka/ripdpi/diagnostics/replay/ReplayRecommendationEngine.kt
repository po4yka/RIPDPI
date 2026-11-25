package com.poyka.ripdpi.diagnostics.replay

import android.content.Context
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplayRecommendationEngine internal constructor(
    private val catalogProvider: () -> ReplayRecommendationCatalog,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this({ loadCatalogFromAssets(context) })

    private val catalog: ReplayRecommendationCatalog by lazy { catalogProvider() }

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

        internal fun forTesting(catalog: ReplayRecommendationCatalog): ReplayRecommendationEngine =
            ReplayRecommendationEngine(catalogProvider = { catalog })

        private fun loadCatalogFromAssets(context: Context): ReplayRecommendationCatalog =
            context.assets.open(CATALOG_ASSET_PATH).bufferedReader().use { reader ->
                ReplayRecommendationCatalogParser.parse(reader.readText())
            }
    }
}

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
