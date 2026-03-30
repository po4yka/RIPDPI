package com.poyka.ripdpi.security

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinLockoutManager
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        internal var timeSource: () -> Long = System::currentTimeMillis

        private var failedAttempts: Int = 0
        private var lockoutEndEpochMs: Long = 0L

        init {
            restore()
        }

        fun isLockedOut(): Boolean = lockoutEndEpochMs != 0L && timeSource() < lockoutEndEpochMs

        fun remainingLockoutMs(): Long {
            if (!isLockedOut()) return 0L
            return (lockoutEndEpochMs - timeSource()).coerceAtLeast(0L)
        }

        fun recordFailure() {
            failedAttempts++
            val delayMs = computeDelay(failedAttempts)
            lockoutEndEpochMs =
                if (delayMs > 0L) timeSource() + delayMs else 0L
            persist()
        }

        fun recordSuccess() {
            failedAttempts = 0
            lockoutEndEpochMs = 0L
            persist()
        }

        private fun computeDelay(attempts: Int): Long {
            val tier = attempts - GRACE_ATTEMPTS
            if (tier < 0) return 0L
            return LOCKOUT_DELAYS.getOrElse(tier) { MAX_LOCKOUT_MS }
        }

        private fun persist() {
            prefs
                .edit()
                .putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
                .putLong(KEY_LOCKOUT_END, lockoutEndEpochMs)
                .commit()
        }

        private fun restore() {
            failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
            lockoutEndEpochMs = prefs.getLong(KEY_LOCKOUT_END, 0L)
        }

        private companion object {
            const val PREFS_NAME = "pin_lockout"
            const val KEY_FAILED_ATTEMPTS = "failed_attempts"
            const val KEY_LOCKOUT_END = "lockout_end_ms"
            const val GRACE_ATTEMPTS = 3
            const val MAX_LOCKOUT_MS = 600_000L
            private val LOCKOUT_DELAYS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)
        }
    }
