package com.poyka.ripdpi.services

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import androidx.annotation.CallSuper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A [VpnService] with a [Lifecycle], so that subclasses can use `lifecycleScope`. The service callbacks run on the
 * main thread and drive the registry directly. Subclasses must call `super` in each callback.
 */
open class LifecycleVpnService :
    VpnService(),
    LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    @CallSuper
    override fun onCreate() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        super.onCreate()
    }

    @CallSuper
    override fun onBind(intent: Intent): IBinder? {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return super.onBind(intent)
    }

    @CallSuper
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return super.onStartCommand(intent, flags, startId)
    }

    @CallSuper
    override fun onDestroy() {
        // Moving to DESTROYED also delivers ON_STOP to observers that reached STARTED.
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
