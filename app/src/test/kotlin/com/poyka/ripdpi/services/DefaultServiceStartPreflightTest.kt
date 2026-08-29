package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.LocalNetworkAccessRequiredException
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultServiceStartPreflightTest {
    @Test
    fun `granted local network permission skips policy resolution`() =
        runTest {
            var resolveCount = 0
            val preflight =
                DefaultServiceStartPreflight(
                    localNetworkGranted = { true },
                    requireLocalNetworkAccess = { resolveCount += 1 },
                )

            assertEquals(ServiceStartPreflightResult.Allowed, preflight.check(Mode.VPN))
            assertEquals(0, resolveCount)
        }

    @Test
    fun `denied permission allows public policy`() =
        runTest {
            val preflight =
                DefaultServiceStartPreflight(
                    localNetworkGranted = { false },
                    requireLocalNetworkAccess = {},
                )

            assertEquals(ServiceStartPreflightResult.Allowed, preflight.check(Mode.Proxy))
        }

    @Test
    fun `denied permission reports local network policy before dispatch`() =
        runTest {
            val preflight =
                DefaultServiceStartPreflight(
                    localNetworkGranted = { false },
                    requireLocalNetworkAccess = { throw LocalNetworkAccessRequiredException() },
                )

            assertEquals(
                ServiceStartPreflightResult.LocalNetworkPermissionRequired,
                preflight.check(Mode.Proxy),
            )
        }
}
