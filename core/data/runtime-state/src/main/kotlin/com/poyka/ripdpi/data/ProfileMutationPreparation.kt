package com.poyka.ripdpi.data

/** Captured before an editor/import/enrollment operation suspends; never replaced after replay. */
data class ProfileMutationPreparation(
    val origin: ProfileMutationOrigin,
    val expectedPauseAuthority: PauseAuthorityRef,
)
