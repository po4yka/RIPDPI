package com.poyka.ripdpi.diagnostics

import java.io.File

/**
 * Public context projected from the internal archive selection so app-layer
 * collectors can read the bits they need without leaking internals of
 * [DiagnosticsArchiveSelection].
 */
data class DeveloperAnalyticsContext(
    val archiveCreatedAtMs: Long,
    val archiveFileName: String,
    val homeRunId: String? = null,
    val homeCompositeOutcome: DiagnosticsHomeCompositeOutcome? = null,
    val primarySessionId: String? = null,
    val primaryProfileId: String? = null,
    val pcapFiles: List<File> = emptyList(),
    val compositeSessionIds: List<String> = emptyList(),
)

/**
 * Collects developer-facing analytics that are serialized into the share archive as
 * `developer-analytics.json`. Implementations live in the app layer because most
 * signals require Android APIs, proc filesystem access, or BuildConfig constants.
 */
interface DeveloperAnalyticsSource {
    suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload
}

object NoopDeveloperAnalyticsSource : DeveloperAnalyticsSource {
    override suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload =
        DeveloperAnalyticsPayload(
            notes = listOf("Developer analytics source not bound — noop payload."),
        )
}
