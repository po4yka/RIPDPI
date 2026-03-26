package com.poyka.ripdpi.services

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import androidx.annotation.CallSuper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher

/**
 * Based on [androidx.lifecycle.LifecycleService]
 */
open class LifecycleVpnService :
    VpnService(),
    LifecycleOwner {
    private val dispatcher by lazy(LazyThreadSafetyMode.NONE) { ServiceLifecycleDispatcher(this) }

    @CallSuper
    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        super.onCreate()
    }

    @CallSuper
    override fun onBind(intent: Intent): IBinder? {
        dispatcher.onServicePreSuperOnBind()
        return super.onBind(intent)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    @CallSuper
    override fun onStart(
        intent: Intent?,
        startId: Int,
    ) {
        dispatcher.onServicePreSuperOnStart()
        invokeDeprecatedOnStart(intent, startId)
    }

    // this method is added only to annotate it with @CallSuper.
    // In usual Service, super.onStartCommand is no-op, but in LifecycleService
    // it results in dispatcher.onServicePreSuperOnStart() call, because
    // super.onStartCommand calls onStart().
    @CallSuper
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = super.onStartCommand(intent, flags, startId)

    @CallSuper
    override fun onDestroy() {
        dispatcher.onServicePreSuperOnDestroy()
        super.onDestroy()
    }

    override val lifecycle: Lifecycle
        get() = dispatcher.lifecycle

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    private fun invokeDeprecatedOnStart(
        intent: Intent?,
        startId: Int,
    ) {
        super.onStart(intent, startId)
    }
}
