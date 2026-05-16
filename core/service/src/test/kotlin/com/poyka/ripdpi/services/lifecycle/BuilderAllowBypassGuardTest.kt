package com.poyka.ripdpi.services.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secure profile rejects allowBypass(true); unsafe-bypass-explicit-flag profile permits it.
 */
class BuilderAllowBypassGuardTest {
    private val guard = BuilderAllowBypassGuard()

    @Test
    fun secureProfileRejectsAllowBypass() {
        val profile = VpnProfile(unsafeAllowBypass = false)
        assertFalse(guard.allow(profile))
    }

    @Test
    fun defaultProfileRejectsAllowBypass() {
        val profile = VpnProfile()
        assertFalse(guard.allow(profile))
    }

    @Test
    fun explicitUnsafeBypassProfilePermitsAllowBypass() {
        val profile = VpnProfile(unsafeAllowBypass = true)
        assertTrue(guard.allow(profile))
    }

    @Test
    fun multipleSecureProfilesAllRejected() {
        val profiles =
            listOf(
                VpnProfile(unsafeAllowBypass = false),
                VpnProfile(),
            )
        profiles.forEach { profile ->
            assertFalse("Secure profile must never permit bypass: $profile", guard.allow(profile))
        }
    }

    @Test
    fun bypassFlagIsSoleGatingCondition() {
        assertFalse(guard.allow(VpnProfile(unsafeAllowBypass = false)))
        assertTrue(guard.allow(VpnProfile(unsafeAllowBypass = true)))
    }
}
