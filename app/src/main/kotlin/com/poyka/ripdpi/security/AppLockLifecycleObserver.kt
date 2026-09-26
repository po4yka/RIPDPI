package com.poyka.ripdpi.security

import android.content.Context
import android.os.SystemClock
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
        internal var elapsedTimeSource: () -> Long = SystemClock::elapsedRealtime

        var onRelockNeeded: (() -> Unit)? = null
        var isAuthenticated: () -> Boolean = { false }
        var isBiometricEnabled: () -> Boolean = { false }

        private var observing = false

        fun startObserving() {
            if (observing) return
            observing = true
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        override fun onStop(owner: LifecycleOwner) {
            prefs
                .edit()
                .putLong(KEY_LAST_BACKGROUNDED, timeSource())
                .putLong(KEY_LAST_BACKGROUNDED_ELAPSED, elapsedTimeSource())
                .commit()
        }

        override fun onStart(owner: LifecycleOwner) {
            if (!isBiometricEnabled() || !isAuthenticated() || !prefs.contains(KEY_LAST_BACKGROUNDED)) return
            val backgroundedElapsedMs = prefs.getLong(KEY_LAST_BACKGROUNDED_ELAPSED, -1L)
            val elapsed = elapsedTimeSource() - backgroundedElapsedMs
            if (backgroundedElapsedMs < 0L || elapsed < 0L || elapsed > GRACE_PERIOD_MS) {
                onRelockNeeded?.invoke()
            }
        }

        internal companion object {
            const val GRACE_PERIOD_MS = 5_000L
            private const val KEY_LAST_BACKGROUNDED = "last_backgrounded_at_ms"
            private const val KEY_LAST_BACKGROUNDED_ELAPSED = "last_backgrounded_at_elapsed_ms"
        }
    }
