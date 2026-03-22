package com.poyka.ripdpi.diagnostics.presentation

import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsProfileProjection(
    val kind: ScanKind,
    val family: DiagnosticProfileFamily,
    val regionTag: String? = null,
    val manualOnly: Boolean = false,
    val packRefs: List<String> = emptyList(),
    val strategyProbeSuiteId: String? = null,
)

@Serializable
data class DiagnosticsSessionProjection(
    val results: List<ProbeResult> = emptyList(),
    val resolverRecommendation: ResolverRecommendation? = null,
    val strategyProbeReport: StrategyProbeReport? = null,
    val diagnoses: List<Diagnosis> = emptyList(),
    val classifierVersion: String? = null,
    val packVersions: Map<String, Int> = emptyMap(),
)

@Serializable
data class DiagnosticsSummaryProjection(
    val sessionLines: List<String> = emptyList(),
    val networkLines: List<String> = emptyList(),
    val contextLines: List<String> = emptyList(),
    val telemetryLines: List<String> = emptyList(),
    val resultLines: List<String> = emptyList(),
    val warningLines: List<String> = emptyList(),
    val diagnoses: List<Diagnosis> = emptyList(),
    val classifierVersion: String? = null,
    val packVersions: Map<String, Int> = emptyMap(),
)
