package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WarpRegistrationAtomicityTest {
    @Test
    fun `selector and Stop during registration scan preserve user choice with no partial profile`() =
        runTest {
            val authority = testPauseAuthority()
            RoomProfileFixture(RuntimeEnvironment.getApplication(), authority).use { room ->
                room.initialize()
                room.coordinator.recover()
                room.groups.add(
                    ProxyGroup(
                        "group",
                        "Group",
                        ProxyGroupType.BASIC,
                        0,
                        true,
                        members =
                            listOf(
                                ProxyProfile.Vless("member", "Member", "group", "example.invalid", 443, "fixture"),
                            ),
                    ),
                )
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val probe =
                    object : WarpEndpointProbe {
                        override suspend fun probe(
                            candidate: WarpEndpointCacheEntry,
                            timeoutMillis: Int,
                        ): WarpEndpointCacheEntry {
                            assertNull(room.warpCredentials.load("registered"))
                            assertNull(room.warpProfiles.load("registered"))
                            entered.complete(Unit)
                            release.await()
                            return candidate.copy(rttMs = 1)
                        }
                    }
                room.settings.update { setWarpScannerEnabled(true) }
                val lock = WarpStoreMutationLock()
                val provisioned =
                    sampleWarpProvisioningResult().let {
                        it.copy(
                            endpoint = it.endpoint.copy(host = null, ipv4 = "127.0.0.1"),
                        )
                    }
                val flow =
                    DefaultWarpEnrollmentFlowService(
                        room.settings,
                        room.warpProfiles,
                        room.warpCredentials,
                        room.warpEndpoints,
                        FakeWarpProvisioningClient(provisioned),
                        PassthroughWarpBootstrapProxyRunner(),
                        DefaultWarpEndpointScanner(room.settings, room.warpEndpoints, probe),
                        DefaultWarpProfileActivationService(
                            room.warpProfiles,
                            room.warpCredentials,
                            room.warpEndpoints,
                            room.coordinator,
                            lock,
                        ),
                        lock,
                        room.coordinator,
                    )
                val registration =
                    async {
                        runCatching {
                            flow.registerConsumerFree(
                                "Registered",
                                WarpRegisterDeviceRequest("public", "private"),
                                "registered",
                                "wifi:current",
                            )
                        }
                    }
                entered.await()
                room.selectorSelections.select("group", "member")
                val stopped = authority.reserveStop()
                val chosenSettings = room.settings.snapshot()
                val chosenSelection = room.selectorSelections.snapshot("group")
                release.complete(Unit)

                assertTrue(registration.await().isFailure)
                assertEquals(stopped.authority, authority.reference())
                assertEquals(chosenSettings, room.settings.snapshot())
                assertEquals(chosenSelection, room.selectorSelections.snapshot("group"))
                assertNull(room.warpProfiles.activeProfileId())
                assertNull(room.warpProfiles.load("registered"))
                assertNull(room.warpCredentials.load("registered"))
                assertTrue(room.warpEndpoints.loadAll("registered").isEmpty())
                room.coordinator.recover()
                assertNull(room.warpProfiles.load("registered"))
            }
        }
}
