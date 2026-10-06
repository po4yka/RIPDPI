package com.poyka.ripdpi.data

sealed interface ProfileUtilitySelectionResult {
    data object Superseded : ProfileUtilitySelectionResult

    data class Selected(
        val receipt: ProfileActivationReceipt,
        val catalogGeneration: Long,
    ) : ProfileUtilitySelectionResult
}
