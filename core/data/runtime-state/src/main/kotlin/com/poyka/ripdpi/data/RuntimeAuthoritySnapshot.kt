package com.poyka.ripdpi.data

/**
 * Phase/desired-state changes may retain the intent generation, so policy authorization compares the whole snapshot.
 */
@kotlinx.serialization.Serializable
data class RuntimeAuthoritySnapshot(
    val reference: PauseAuthorityRef,
    val desired: DesiredRuntimeState,
    val desiredMode: String?,
    val pause: PauseIntent?,
    /** Same-generation bind, claim and ACK transitions must fence policy decisions too. */
    val command: DurableCommandRecord?,
)
