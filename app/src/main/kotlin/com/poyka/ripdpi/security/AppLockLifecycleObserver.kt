package com.poyka.ripdpi.security

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockLifecycleObserver
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : DefaultLifecycleObserver {
        private val prefs: android.content.SharedPreferences =
            context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
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
            prefs.edit().putLong(KEY_LAST_BACKGROUNDED, lastBackgroundedAtMs).commit()
        }

        override fun onStart(owner: LifecycleOwner) {
            lastBackgroundedAtMs = prefs.getLong(KEY_LAST_BACKGROUNDED, 0L)
            if (!isBiometricEnabled() || !isAuthenticated() || lastBackgroundedAtMs == 0L) return
            val elapsed = timeSource() - lastBackgroundedAtMs
            if (elapsed > GRACE_PERIOD_MS) {
                onRelockNeeded?.invoke()
            }
        }

        internal companion object {
            const val GRACE_PERIOD_MS = 5_000L
            private const val KEY_LAST_BACKGROUNDED = "last_backgrounded_at_ms"
        }
    }
