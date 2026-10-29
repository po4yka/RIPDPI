@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

data class DiagnosticsArchive(
    val fileName: String,
    val absolutePath: String,
    val sessionId: String?,
    val createdAt: Long,
    val scope: String,
    val schemaVersion: Int,
    val privacyMode: String,
)

data class SummaryMetric(
    val label: String,
    val value: String,
)

data class ShareSummary(
    val title: String,
    val body: String,
    val compactMetrics: List<SummaryMetric> = emptyList(),
)

@Serializable
data class BundledDiagnosticProfile(
    val id: String,
    val name: String,
    val version: Int,
    val request: ScanRequest,
)
