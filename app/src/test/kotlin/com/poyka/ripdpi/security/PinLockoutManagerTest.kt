package com.poyka.ripdpi.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PinLockoutManagerTest {
    private lateinit var manager: PinLockoutManager
    private var currentTimeMs = 1_000_000L

    @Before
    fun setUp() {
        manager = PinLockoutManager(ApplicationProvider.getApplicationContext())
        manager.timeSource = { currentTimeMs }
    }

    @Test
    fun `first two failures do not trigger lockout`() {
        manager.recordFailure()
        assertFalse(manager.isLockedOut())
        assertEquals(0L, manager.remainingLockoutMs())

        manager.recordFailure()
        assertFalse(manager.isLockedOut())
        assertEquals(0L, manager.remainingLockoutMs())
    }

    @Test
    fun `third failure triggers 30 second lockout`() {
        repeat(3) { manager.recordFailure() }
        assertTrue(manager.isLockedOut())
        assertEquals(30_000L, manager.remainingLockoutMs())
    }

    @Test
    fun `fourth failure triggers 60 second lockout`() {
        repeat(4) { manager.recordFailure() }
        assertTrue(manager.isLockedOut())
        assertEquals(60_000L, manager.remainingLockoutMs())
    }

    @Test
    fun `fifth failure triggers 120 second lockout`() {
        repeat(5) { manager.recordFailure() }
        assertTrue(manager.isLockedOut())
        assertEquals(120_000L, manager.remainingLockoutMs())
    }

    @Test
    fun `sixth failure triggers 300 second lockout`() {
        repeat(6) { manager.recordFailure() }
        assertTrue(manager.isLockedOut())
        assertEquals(300_000L, manager.remainingLockoutMs())
    }

    @Test
    fun `seventh and subsequent failures cap at 600 seconds`() {
        repeat(7) { manager.recordFailure() }
        assertTrue(manager.isLockedOut())
        assertEquals(600_000L, manager.remainingLockoutMs())

        // Expire lockout, then fail again
        currentTimeMs += 600_001L
        assertFalse(manager.isLockedOut())

        manager.recordFailure()
        assertTrue(manager.isLockedOut())
        assertEquals(600_000L, manager.remainingLockoutMs())
    }

    @Test
    fun `lockout expires after delay elapses`() {
        repeat(3) { manager.recordFailure() }
        assertTrue(manager.isLockedOut())

        currentTimeMs += 30_001L
        assertFalse(manager.isLockedOut())
        assertEquals(0L, manager.remainingLockoutMs())
    }

    @Test
    fun `success resets attempt counter`() {
        repeat(3) { manager.recordFailure() }
        assertTrue(manager.isLockedOut())

        // Expire lockout so we can call recordSuccess
        currentTimeMs += 30_001L
        manager.recordSuccess()

        // Next failures start from zero again
        manager.recordFailure()
        assertFalse(manager.isLockedOut())

        manager.recordFailure()
        assertFalse(manager.isLockedOut())

        manager.recordFailure()
        assertTrue(manager.isLockedOut())
        assertEquals(30_000L, manager.remainingLockoutMs())
    }

    @Test
    fun `remaining lockout decreases over time`() {
        repeat(3) { manager.recordFailure() }
        assertEquals(30_000L, manager.remainingLockoutMs())

        currentTimeMs += 10_000L
        assertEquals(20_000L, manager.remainingLockoutMs())
    }

    @Test
    fun `not locked out initially`() {
        assertFalse(manager.isLockedOut())
        assertEquals(0L, manager.remainingLockoutMs())
    }
}
