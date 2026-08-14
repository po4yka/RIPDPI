package com.poyka.ripdpi.activities

import com.poyka.ripdpi.security.AppLockLifecycleObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainAppLockLifecycleCoordinatorTest {
    @Test
    fun `start wires relock callback and clears authentication`() {
        val observer = AppLockLifecycleObserver(RuntimeEnvironment.getApplication())
        val coordinator = MainAppLockLifecycleCoordinator(observer)
        var biometricEnabled = true
        var relockCount = 0

        coordinator.start(
            isBiometricEnabled = { biometricEnabled },
            onRelockNeeded = { relockCount += 1 },
        )

        assertFalse(observer.isAuthenticated())
        assertTrue(observer.isBiometricEnabled())

        coordinator.onAuthenticated()
        assertTrue(observer.isAuthenticated())

        observer.onRelockNeeded?.invoke()

        assertFalse(observer.isAuthenticated())
        assertEquals(1, relockCount)

        biometricEnabled = false
        assertFalse(observer.isBiometricEnabled())
    }
}
