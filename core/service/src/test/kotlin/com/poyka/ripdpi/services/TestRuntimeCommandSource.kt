package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.testPauseAuthority
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** A fixture user owns a checked command before the real native coordinator can suspend. */
internal class TestRuntimeCommandSource(
    private val mode: Mode,
) {
    val authority = testPauseAuthority()
    private var original: RuntimeAppliedIntent? = null

    suspend fun start(
        alreadyRunning: Boolean,
        block: suspend () -> Unit,
    ) {
        val captured =
            currentCoroutineContext()[RuntimeCommandStartAuthority]?.original
                ?: original?.takeIf { alreadyRunning }
                ?: RuntimeAppliedIntent.Activation(authority.reserveStart(mode))
        original = captured
        withContext(RuntimeCommandStartAuthority(captured)) { block() }
    }
}
