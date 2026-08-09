package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsArchiveTargetAliasesPayload(
    val aliasVersion: String = "target_aliases_v1",
    val scope: DiagnosticsArchiveTargetAliasScope = DiagnosticsArchiveTargetAliasScope.ARCHIVE_LOCAL,
    val aliases: List<DiagnosticsArchiveTargetAliasEntry>,
)

@Serializable
internal data class DiagnosticsArchiveTargetAliasEntry(
    val alias: String,
    val occurrenceCount: Int,
    val evidenceRefs: List<String>,
)

@Serializable
internal enum class DiagnosticsArchiveTargetAliasScope {
    ARCHIVE_LOCAL,
}
