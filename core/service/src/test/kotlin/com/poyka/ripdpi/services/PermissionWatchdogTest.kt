package com.poyka.ripdpi.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionWatchdogTest {
    private class FakePermissionChecker(
        initial: RuntimePermissionState,
    ) : RuntimePermissionChecker {
        var state: RuntimePermissionState = initial

        override fun check(): RuntimePermissionState = state
    }

    @Test
    fun `no events when permissions stay granted`() =
        runTest {
            val checker =
                FakePermissionChecker(
                    RuntimePermissionState(notificationsGranted = true, vpnConsentGranted = true),
                )
            val events = mutableListOf<PermissionChangeEvent>()
            val job =
                launch {
                    pollPermissionChanges(checker, intervalMs = 100L, clock = { 0L }).collect {
                        events.add(it)
                    }
                }

            advanceTimeBy(500L)
            assertTrue(events.isEmpty())
            job.cancel()
        }

    @Test
    fun `emits event when notifications permission revoked`() =
        runTest {
            val checker =
                FakePermissionChecker(
                    RuntimePermissionState(notificationsGranted = true, vpnConsentGranted = true),
                )
            val events = mutableListOf<PermissionChangeEvent>()
            val job =
                launch {
                    pollPermissionChanges(checker, intervalMs = 100L, clock = { 1000L }).collect {
                        events.add(it)
                    }
                }

            advanceTimeBy(50L)
            checker.state = checker.state.copy(notificationsGranted = false)
            advanceTimeBy(100L)

            assertEquals(1, events.size)
            assertEquals(PermissionChangeEvent.KIND_NOTIFICATIONS, events[0].kind)
            assertEquals(1000L, events[0].detectedAt)
            job.cancel()
        }

    @Test
    fun `emits event when vpn consent revoked`() =
        runTest {
            val checker =
                FakePermissionChecker(
                    RuntimePermissionState(notificationsGranted = true, vpnConsentGranted = true),
                )
            val events = mutableListOf<PermissionChangeEvent>()
            val job =
                launch {
                    pollPermissionChanges(checker, intervalMs = 100L, clock = { 2000L }).collect {
                        events.add(it)
                    }
                }

            advanceTimeBy(50L)
            checker.state = checker.state.copy(vpnConsentGranted = false)
            advanceTimeBy(100L)

            assertEquals(1, events.size)
            assertEquals(PermissionChangeEvent.KIND_VPN_CONSENT, events[0].kind)
            job.cancel()
        }

    @Test
    fun `emits both events when both permissions revoked simultaneously`() =
        runTest {
            val checker =
                FakePermissionChecker(
                    RuntimePermissionState(notificationsGranted = true, vpnConsentGranted = true),
                )
            val collected =
                pollPermissionChanges(checker, intervalMs = 100L, clock = { 0L })
                    .take(2)

            val job =
                launch {
                    advanceTimeBy(50L)
                    checker.state = RuntimePermissionState(notificationsGranted = false, vpnConsentGranted = false)
                    advanceTimeBy(100L)
                }

            val events = collected.toList()
            job.cancel()

            assertEquals(2, events.size)
            val kinds = events.map { it.kind }.toSet()
            assertTrue(PermissionChangeEvent.KIND_NOTIFICATIONS in kinds)
            assertTrue(PermissionChangeEvent.KIND_VPN_CONSENT in kinds)
        }

    @Test
    fun `no event for non-granted to non-granted`() =
        runTest {
            val checker =
                FakePermissionChecker(
                    RuntimePermissionState(notificationsGranted = false, vpnConsentGranted = false),
                )
            val events = mutableListOf<PermissionChangeEvent>()
            val job =
                launch {
                    pollPermissionChanges(checker, intervalMs = 100L, clock = { 0L }).collect {
                        events.add(it)
                    }
                }

            advanceTimeBy(500L)
            assertTrue(events.isEmpty())
            job.cancel()
        }

    @Test
    fun `emits second event after re-grant and re-revoke`() =
        runTest {
            val checker =
                FakePermissionChecker(
                    RuntimePermissionState(notificationsGranted = true, vpnConsentGranted = true),
                )
            val events = mutableListOf<PermissionChangeEvent>()
            val job =
                launch {
                    pollPermissionChanges(checker, intervalMs = 100L, clock = { 0L }).collect {
                        events.add(it)
                    }
                }

            // First revocation
            advanceTimeBy(50L)
            checker.state = checker.state.copy(notificationsGranted = false)
            advanceTimeBy(100L)
            assertEquals(1, events.size)

            // Re-grant
            checker.state = checker.state.copy(notificationsGranted = true)
            advanceTimeBy(100L)
            assertEquals(1, events.size) // no event for grant

            // Second revocation
            checker.state = checker.state.copy(notificationsGranted = false)
            advanceTimeBy(100L)
            assertEquals(2, events.size)

            job.cancel()
        }
}
