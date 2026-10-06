package com.poyka.ripdpi.data

/**
 * Phase/desired-state changes may retain the intent generation, so policy authorization compares the whole snapshot.
 */
data class RuntimeAuthoritySnapshot(
    val reference: PauseAuthorityRef,
    val desired: DesiredRuntimeState,
    val desiredMode: String?,
    val pause: PauseIntent?,
)

/** Standing start-on-boot preference, distinct from an explicit user Start. */
class BootPolicyStartReceipt internal constructor(
    val reference: PauseAuthorityRef,
)
