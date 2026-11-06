package com.poyka.ripdpi.data.selector

import com.poyka.ripdpi.data.ProfileActivationReceipt

/** Reconstruction persists a saved choice without manufacturing a live activation capability. */
sealed interface SelectorChoiceOrigin {
    data class Manual(
        val receipt: ProfileActivationReceipt,
    ) : SelectorChoiceOrigin

    data object Reconstruction : SelectorChoiceOrigin
}

interface SelectorChoicePersistence {
    suspend fun commitMember(
        groupId: String,
        memberId: String,
        origin: SelectorChoiceOrigin,
    )

    suspend fun clearStandalone()

    suspend fun snapshotActiveGroupId(): String?

    /** Compensation restores metadata only; no old activation receipt is resurrected. */
    suspend fun restoreActiveGroup(groupId: String?)

    suspend fun prune(groupIds: Set<String>)
}
