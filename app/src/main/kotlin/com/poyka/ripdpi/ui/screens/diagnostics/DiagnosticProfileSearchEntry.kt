package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.ui.components.profiles.matchesProfileQuery

internal data class DiagnosticProfileSearchEntry(
    val profile: DiagnosticsProfileOptionUiModel,
    val familyLabel: String,
    val description: String,
)

internal fun filterDiagnosticProfiles(
    entries: List<DiagnosticProfileSearchEntry>,
    query: String,
    family: String?,
): List<DiagnosticProfileSearchEntry> =
    entries.filter { entry ->
        (family == null || entry.profile.family.name == family) &&
            matchesProfileQuery(
                query,
                listOf(entry.profile.name, entry.profile.source, entry.familyLabel, entry.description),
            )
    }
