package com.poyka.ripdpi.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
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
        internal var elapsedTimeSource: () -> Long = SystemClock::elapsedRealtime
        internal var bootCountSource: () -> Int = {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }

        private var failedAttempts: Int = 0
        private var lockoutEndEpochMs: Long = 0L
        private var lockoutEndElapsedMs: Long = 0L
        private var lockoutBootCount: Int = -1

        init {
            restore()
        }

        fun isLockedOut(): Boolean {
            if (lockoutEndElapsedMs == 0L) return false
            if (bootCountSource() != lockoutBootCount) restartLockout()
            val lockedOut = elapsedTimeSource() < lockoutEndElapsedMs
            if (!lockedOut) {
                lockoutEndElapsedMs = 0L
                lockoutEndEpochMs = 0L
                persist()
            }
            return lockedOut
        }

        fun remainingLockoutMs(): Long {
            if (!isLockedOut()) return 0L
            return (lockoutEndElapsedMs - elapsedTimeSource()).coerceAtLeast(0L)
        }

        fun recordFailure() {
            failedAttempts++
            val delayMs = computeDelay(failedAttempts)
            lockoutEndEpochMs =
                if (delayMs > 0L) timeSource() + delayMs else 0L
            lockoutEndElapsedMs =
                if (delayMs > 0L) elapsedTimeSource() + delayMs else 0L
            lockoutBootCount = bootCountSource()
            persist()
        }

        fun recordSuccess() {
            failedAttempts = 0
            lockoutEndEpochMs = 0L
            lockoutEndElapsedMs = 0L
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
                .putLong(KEY_LOCKOUT_END_ELAPSED, lockoutEndElapsedMs)
                .putInt(KEY_BOOT_COUNT, lockoutBootCount)
                .commit()
        }

        private fun restore() {
            failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
            lockoutEndEpochMs = prefs.getLong(KEY_LOCKOUT_END, 0L)
            lockoutEndElapsedMs = prefs.getLong(KEY_LOCKOUT_END_ELAPSED, 0L)
            lockoutBootCount = prefs.getInt(KEY_BOOT_COUNT, -1)
            val currentBootCount = bootCountSource()
            val needsNewDeadline =
                lockoutEndElapsedMs == 0L || currentBootCount < 0 || currentBootCount != lockoutBootCount
            if (failedAttempts >= GRACE_ATTEMPTS && lockoutEndEpochMs != 0L &&
                needsNewDeadline
            ) {
                restartLockout()
            }
        }

        private fun restartLockout() {
            val delayMs = computeDelay(failedAttempts)
            lockoutEndEpochMs = timeSource() + delayMs
            lockoutEndElapsedMs = elapsedTimeSource() + delayMs
            lockoutBootCount = bootCountSource()
            persist()
        }

        private companion object {
            const val PREFS_NAME = "pin_lockout"
            const val KEY_FAILED_ATTEMPTS = "failed_attempts"
            const val KEY_LOCKOUT_END = "lockout_end_ms"
            const val KEY_LOCKOUT_END_ELAPSED = "lockout_end_elapsed_ms"
            const val KEY_BOOT_COUNT = "lockout_boot_count"
            const val GRACE_ATTEMPTS = 3
            const val MAX_LOCKOUT_MS = 600_000L
            private val LOCKOUT_DELAYS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)
        }
    }
