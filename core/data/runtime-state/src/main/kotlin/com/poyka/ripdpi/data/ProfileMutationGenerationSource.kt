package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.StateFlow

/** Invalidates consumers only after a catalog mutation or recovery has completed durably. */
interface ProfileMutationGenerationSource {
    val generation: StateFlow<Long>
}
