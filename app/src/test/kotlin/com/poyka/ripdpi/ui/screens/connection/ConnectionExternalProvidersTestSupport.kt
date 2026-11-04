package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal object NoExternalProviders : ConnectionExternalProviders {
    override fun current(mode: Mode): ConnectionExternalProvider? = null

    override suspend fun deactivate(mode: Mode): Boolean = false
}

internal class FakeExternalProviders(
    var active: ConnectionExternalProvider?,
    private val reconnectAfterDeactivate: Boolean = true,
) : ConnectionExternalProviders {
    var deactivations = 0
        private set

    override fun current(mode: Mode): ConnectionExternalProvider? = active.takeIf { mode == Mode.VPN }

    override suspend fun deactivate(mode: Mode): Boolean {
        if (current(mode) == null) return false
        deactivations += 1
        active = null
        return reconnectAfterDeactivate
    }
}

internal val TestApplicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
