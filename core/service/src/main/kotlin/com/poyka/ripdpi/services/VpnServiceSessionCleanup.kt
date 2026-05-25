package com.poyka.ripdpi.services

import java.util.concurrent.atomic.AtomicBoolean

internal class VpnServiceSessionCleanup {
    private val nativeProtectCleaned = AtomicBoolean(false)
    private val coordinatorDestroyed = AtomicBoolean(false)

    fun cleanupNativeProtect(
        unregisterNativeProtect: () -> Unit,
        stopProtectSocketServer: () -> Unit,
    ) {
        if (!nativeProtectCleaned.compareAndSet(false, true)) return
        unregisterNativeProtect()
        stopProtectSocketServer()
    }

    fun destroyCoordinator(destroy: () -> Unit) {
        if (!coordinatorDestroyed.compareAndSet(false, true)) return
        destroy()
    }
}
