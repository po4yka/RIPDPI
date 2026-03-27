package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class ProbePersistencePolicy {
    MANUAL_ONLY,
    BACKGROUND_ONLY,
    ALWAYS,
}
