package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal object NoExternalProviders : ConnectionExternalProviders {
    override suspend fun current(mode: Mode): ConnectionExternalProvider? = null

    override suspend fun deactivate(mode: Mode): Boolean = false
}

internal class FakeExternalProviders(
    var active: ConnectionExternalProvider?,
    private val stopsSession: Boolean = false,
    private val refuses: Boolean = false,
    private val onStop: () -> Unit = {},
) : ConnectionExternalProviders {
    var deactivations = 0
        private set

    override suspend fun current(mode: Mode): ConnectionExternalProvider? = active.takeIf { mode == Mode.VPN }

    override suspend fun deactivate(mode: Mode): Boolean {
        val willStop = current(mode) != null && stopsSession && !refuses
        if (current(mode) != null) deactivations += 1
        if (!refuses) active = null
        if (willStop) onStop()
        return willStop
    }
}

internal val TestApplicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
