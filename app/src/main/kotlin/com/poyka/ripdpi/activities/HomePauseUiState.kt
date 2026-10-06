package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.PausePhase

data class HomePauseUiState(
    val available: Boolean = false,
    val phase: PausePhase? = null,
    val mode: com.poyka.ripdpi.data.Mode? = null,
    val deadlineWallMillis: Long? = null,
    val remainingMillis: Long? = null,
    val failure: com.poyka.ripdpi.data.PauseFailure? = null,
    val requestFailed: Boolean = false,
)
