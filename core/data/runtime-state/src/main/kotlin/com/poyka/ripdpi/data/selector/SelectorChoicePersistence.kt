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
    fun commitMember(
        groupId: String,
        memberId: String,
        origin: SelectorChoiceOrigin,
    )

    fun clearStandalone()

    fun snapshotActiveGroupId(): String?

    /** Compensation restores metadata only; no old activation receipt is resurrected. */
    fun restoreActiveGroup(groupId: String?)

    fun prune(groupIds: Set<String>)
}
