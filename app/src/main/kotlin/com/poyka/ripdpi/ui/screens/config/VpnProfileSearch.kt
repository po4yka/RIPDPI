package com.poyka.ripdpi.ui.screens.config

import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.ui.components.profiles.matchesProfileQuery

internal fun filterRelayProfiles(
    profiles: List<RelayProfileUiState>,
    query: String,
    kind: String?,
): List<RelayProfileUiState> =
    profiles.filter { profile ->
        (kind == null || profile.kind == kind) &&
            matchesProfileQuery(
                query,
                listOf(
                    profile.id,
                    profile.kind,
                    profile.kindLabel,
                    profile.operatorName,
                    profile.jurisdiction,
                    profile.selectorLabel,
                    profile.trustLabel,
                ),
            )
    }
