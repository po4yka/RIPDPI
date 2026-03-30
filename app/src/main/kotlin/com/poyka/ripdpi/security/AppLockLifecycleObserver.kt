package com.poyka.ripdpi.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockLifecycleObserver
    @Inject
    constructor() : DefaultLifecycleObserver {
        internal var timeSource: () -> Long = System::currentTimeMillis

        var onRelockNeeded: (() -> Unit)? = null
        var isAuthenticated: () -> Boolean = { false }
        var isBiometricEnabled: () -> Boolean = { false }

        private var lastBackgroundedAtMs: Long = 0L
        private var observing = false

        fun startObserving() {
            if (observing) return
            observing = true
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        override fun onStop(owner: LifecycleOwner) {
            lastBackgroundedAtMs = timeSource()
        }

        override fun onStart(owner: LifecycleOwner) {
            if (!isBiometricEnabled() || !isAuthenticated() || lastBackgroundedAtMs == 0L) return
            val elapsed = timeSource() - lastBackgroundedAtMs
            if (elapsed > GRACE_PERIOD_MS) {
                onRelockNeeded?.invoke()
            }
        }

        internal companion object {
            const val GRACE_PERIOD_MS = 5_000L
        }
    }
