package com.poyka.ripdpi.diagnostics

object NoopHomeAnalysisAugmentationSource : HomeAnalysisAugmentationSource {
    override suspend fun networkCharacter(): HomeNetworkCharacterSummary? = null

    override suspend fun routingSanity(): HomeRoutingSanitySummary? = null

    override suspend fun bufferbloat(): HomeBufferbloatResult? = null

    override suspend fun dnsCharacterization(): HomeDnsCharacterization? = null
}

object NoopHomeDetectionStageRunner : HomeDetectionStageRunner {
    override suspend fun run(onProgress: suspend (label: String, detail: String) -> Unit): HomeDetectionStageOutcome? =
        null
}

object NoopHomeDetectorCatalogSource : HomeDetectorCatalogSource {
    override fun snapshot(): HomeDetectorCatalogSnapshot = HomeDetectorCatalogSnapshot()
}

object NoopDeveloperAnalyticsSource : DeveloperAnalyticsSource {
    override suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload =
        DeveloperAnalyticsPayload(
            notes = listOf("Developer analytics source not bound — noop payload."),
        )
}

internal object EmptyBundledDiagnosticsCatalogOverrideSource : BundledDiagnosticsCatalogOverrideSource {
    override fun readProfilesJsonOrNull(): String? = null
}
