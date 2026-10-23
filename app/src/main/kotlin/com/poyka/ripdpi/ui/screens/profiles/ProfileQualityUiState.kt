package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.data.ProfileQualityBudget
import com.poyka.ripdpi.data.ProfileQualityDecision
import com.poyka.ripdpi.data.ProfileQualityKey
import com.poyka.ripdpi.data.ProfileQualityObservation
import com.poyka.ripdpi.data.ProfileQualitySnapshot
import com.poyka.ripdpi.data.ProfileUtilityReference

internal data class ProfileQualityUiState(
    val observation: ProfileQualityObservation?,
    val budget: ProfileQualityBudget?,
    val decision: ProfileQualityDecision?,
)

internal fun ProfileQualitySnapshot.forProfile(reference: ProfileUtilityReference): ProfileQualityUiState? {
    if (reference !is ProfileUtilityReference.SelectorMember) return null
    val observation = observations[ProfileQualityKey(reference.groupId, reference.memberId)]
    val budget = budgets[reference.groupId]
    val decision = decisions[reference.groupId]
    return if (observation == null && budget == null && decision == null) {
        null
    } else {
        ProfileQualityUiState(observation, budget, decision)
    }
}
