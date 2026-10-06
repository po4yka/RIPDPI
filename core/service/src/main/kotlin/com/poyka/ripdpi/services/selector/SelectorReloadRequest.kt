package com.poyka.ripdpi.services.selector

import com.poyka.ripdpi.data.ProfileActivationReceipt

/** Group-qualified event. A manual reservation is captured at publication and never read afresh at dispatch. */
data class SelectorReloadRequest(
    val groupId: String,
    val memberId: String,
    val manualReceipt: ProfileActivationReceipt?,
) {
    override fun toString() = "SelectorReloadRequest([REDACTED])"
}
