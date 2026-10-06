package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ProfileUtilitySelectionLease

/** Completed HTTP measurement and its immutable input proof, retained only until applied or rejected. */
interface MeasuredProfileSelectionLease : ProfileUtilitySelectionLease {
    val configurationProof: CandidateConfigurationProof
}
