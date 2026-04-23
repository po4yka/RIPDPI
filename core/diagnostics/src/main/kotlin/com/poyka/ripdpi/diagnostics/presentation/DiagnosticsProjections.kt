package com.poyka.ripdpi.diagnostics.presentation

import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DiagnosticsLegalSafety
import com.poyka.ripdpi.diagnostics.DiagnosticsProfileIntentBucket
import com.poyka.ripdpi.diagnostics.DirectModeVerdict
import com.poyka.ripdpi.diagnostics.ObservationFact
import com.poyka.ripdpi.diagnostics.ProbePersistencePolicy
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsExecutionPolicyProjection(
    val manualOnly: Boolean = false,
    val allowBackground: Boolean = false,
    val requiresRawPath: Boolean = false,
    val probePersistencePolicy: ProbePersistencePolicy = ProbePersistencePolicy.MANUAL_ONLY,
)

@Serializable
data class DiagnosticsProfileProjection(
    val kind: ScanKind,
    val family: DiagnosticProfileFamily,
    val intentBucket: DiagnosticsProfileIntentBucket = DiagnosticsProfileIntentBucket.SAFE_DEFAULT,
    val legalSafety: DiagnosticsLegalSafety = DiagnosticsLegalSafety.SAFE,
    val regionTag: String? = null,
    val executionPolicy: DiagnosticsExecutionPolicyProjection = DiagnosticsExecutionPolicyProjection(),
    val manualOnly: Boolean = false,
    val packRefs: List<String> = emptyList(),
    val strategyProbeSuiteId: String? = null,
)

@Serializable
data class DiagnosticsSessionProjection(
    val results: List<ProbeResult> = emptyList(),
    val resolverRecommendation: ResolverRecommendation? = null,
    val directModeVerdict: DirectModeVerdict? = null,
    val strategyProbeReport: StrategyProbeReport? = null,
    val observations: List<ObservationFact> = emptyList(),
    val engineAnalysisVersion: String? = null,
    val diagnoses: List<Diagnosis> = emptyList(),
    val classifierVersion: String? = null,
    val packVersions: Map<String, Int> = emptyMap(),
)

@Serializable
data class DiagnosticsSummarySection(
    val title: String,
    val lines: List<String> = emptyList(),
)

@Serializable
data class DiagnosticsHighlight(
    val title: String,
    val summary: String,
)

@Serializable
data class DiagnosticsSummaryDocument(
    val header: DiagnosticsSummarySection = DiagnosticsSummarySection(title = "Header"),
    val reportMetadata: DiagnosticsSummarySection = DiagnosticsSummarySection(title = "Report"),
    val environment: DiagnosticsSummarySection = DiagnosticsSummarySection(title = "Environment"),
    val telemetry: DiagnosticsSummarySection = DiagnosticsSummarySection(title = "Telemetry"),
    val rawPreview: DiagnosticsSummarySection = DiagnosticsSummarySection(title = "Raw Preview"),
    val warnings: DiagnosticsSummarySection = DiagnosticsSummarySection(title = "Warnings"),
    val diagnoses: List<Diagnosis> = emptyList(),
    val highlights: List<DiagnosticsHighlight> = emptyList(),
    val observations: List<ObservationFact> = emptyList(),
    val engineAnalysisVersion: String? = null,
    val classifierVersion: String? = null,
    val packVersions: Map<String, Int> = emptyMap(),
)
