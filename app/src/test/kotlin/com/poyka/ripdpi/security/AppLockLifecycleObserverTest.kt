package com.poyka.ripdpi.security

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppLockLifecycleObserverTest {
    @Test
    fun `moving wall clock backward does not defer relock`() {
        val observer = AppLockLifecycleObserver(RuntimeEnvironment.getApplication())
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        var wallTimeMs = 10_000L
        var elapsedTimeMs = 10_000L
        var relocks = 0
        observer.timeSource = { wallTimeMs }
        observer.elapsedTimeSource = { elapsedTimeMs }
        observer.isAuthenticated = { true }
        observer.isBiometricEnabled = { true }
        observer.onRelockNeeded = { relocks++ }

        observer.onStop(owner)
        wallTimeMs = 1_000L
        elapsedTimeMs += 6_000L
        observer.onStart(owner)

        assertEquals(1, relocks)
    }

    @Test
    fun `wall clock at epoch zero does not disable relock`() {
        val observer = AppLockLifecycleObserver(RuntimeEnvironment.getApplication())
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        var elapsedTimeMs = 10_000L
        var relocks = 0
        observer.timeSource = { 0L }
        observer.elapsedTimeSource = { elapsedTimeMs }
        observer.isAuthenticated = { true }
        observer.isBiometricEnabled = { true }
        observer.onRelockNeeded = { relocks++ }

        observer.onStop(owner)
        elapsedTimeMs += 6_000L
        observer.onStart(owner)

        assertEquals(1, relocks)
    }
}
