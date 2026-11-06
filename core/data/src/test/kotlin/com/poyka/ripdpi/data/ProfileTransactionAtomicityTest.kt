package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTransactionAtomicityTest : RoomProfileTest() {
    @Test
    fun `failed profile operations retain complete database state after reopen`() =
        runTest {
            for (family in AtomicProfileFamily.entries) {
                val fixture = fixture()
                fixture.initialize()
                fixture.settings.update { setProxyPort(18080) }
                fixture.relayProfiles.save(RelayProfileRecord("keep", RelayKindTrojan, server = "keep.example"))
                val before = fixture.snapshot()
                if (family == AtomicProfileFamily.AWG) {
                    withContext(Dispatchers.IO) {
                        fixture.room.database.openHelper.writableDatabase.execSQL(
                            "CREATE TRIGGER reject_awg BEFORE INSERT ON awg_profiles " +
                                "BEGIN SELECT RAISE(ABORT, 'AWG write failed'); END",
                        )
                    }
                } else {
                    fixture.failWrite(family.namespace)
                }

                assertTrue("$family must fail", runCatching { family.write(fixture) }.isFailure)
                assertEquals("$family must roll back all rows", before, fixture.snapshot())
                assertEquals(0L, fixture.mutationGeneration.generation.value)
                fixture.clearFaults()
                fixture.reopen()
                assertEquals("$family must not replay after reopen", before, fixture.snapshot())
            }
        }

    @Test
    fun `successful operations persist all profile parts and selection across reopen`() =
        runTest {
            for (family in AtomicProfileFamily.entries) {
                val fixture = fixture()
                fixture.initialize()
                family.write(fixture)
                family.assertSaved(fixture)
                assertEquals(1L, fixture.mutationGeneration.generation.value)
                val committed = fixture.snapshot()
                fixture.reopen()
                family.assertSaved(fixture)
                assertEquals(committed, fixture.snapshot())
                fixture.coordinator().recover()
                assertEquals(committed, fixture.snapshot())
                assertEquals(0L, fixture.mutationGeneration.generation.value)
            }
        }

    @Test
    fun `cancellation during every profile transaction rolls back before return`() =
        runTest {
            for (family in AtomicProfileFamily.entries) {
                val fixture = fixture()
                fixture.initialize()
                val before = fixture.snapshot()
                val barrier = ProfileWriteBarrier(family.namespace)
                fixture.room.beforeSeal = barrier::beforeSeal
                val operation = async { family.write(fixture) }
                try {
                    barrier.awaitEntry()
                    operation.cancel()
                } finally {
                    barrier.release()
                }
                operation.join()
                assertTrue(operation.isCancelled)
                fixture.clearFaults()
                assertEquals("$family cancellation must restore the old state", before, fixture.snapshot())
                fixture.reopen()
                assertEquals(before, fixture.snapshot())
            }
        }

    @Test
    fun `generation is published after commit and failed selection never returns activation`() =
        runTest {
            val fixture = fixture()
            fixture.initialize()
            fixture.room.beforeSeal = { _, _ -> assertEquals(0L, fixture.mutationGeneration.generation.value) }
            AtomicProfileFamily.RELAY.write(fixture)
            assertEquals(1L, fixture.mutationGeneration.generation.value)
            fixture.failWrite("app_settings")
            val before = fixture.snapshot()
            val failure =
                runCatching {
                    fixture.coordinator().selectNativeProvider(
                        fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitActivation),
                        XrayProviderSelectionRecord(),
                        Mode.Proxy.preferenceValue,
                    )
                }
            assertTrue(failure.isFailure)
            assertEquals(before, fixture.snapshot())
            assertEquals(1L, fixture.mutationGeneration.generation.value)
            assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
        }
}

private enum class AtomicProfileFamily(
    val namespace: String,
) {
    RELAY("relay_credentials"),
    XRAY("xray_metadata"),
    WARP("warp_endpoints"),
    AWG("awg_credentials"),
    ;

    suspend fun write(fixture: ProfileMutationTestFixture) {
        val coordinator = fixture.coordinator()
        val preparation = coordinator.captureMutation(ProfileMutationOrigin.SavedEdit)
        when (this) {
            RELAY -> {
                coordinator.upsertRelay(
                    preparation,
                    RelayProfileRecord("relay", RelayKindTrojan, server = "relay.example"),
                    RelayCredentialRecord("relay", trojanPassword = "fixture-relay-secret"),
                    true,
                    true,
                    modeAfterImage = Mode.Proxy.preferenceValue,
                    xraySelectionAfterImage = XrayProviderSelectionRecord(),
                )
            }

            XRAY -> {
                coordinator.upsertXrayProvider(
                    preparation,
                    "xray",
                    testXrayProfile(),
                    XrayProviderSelectionRecord(XrayProviderSelectionRecord.ProviderKindXray, "xray"),
                    Mode.VPN.preferenceValue,
                )
            }

            WARP -> {
                coordinator.upsertWarp(
                    preparation,
                    WarpProfile(id = "warp", displayName = "WARP"),
                    WarpCredentials(profileId = "warp", deviceId = "device", accessToken = "fixture-warp-secret"),
                    listOf(
                        WarpEndpointCacheEntry(
                            profileId = "warp",
                            networkScopeKey = GlobalWarpEndpointScopeKey,
                            host = "warp.example",
                            port = 2408,
                        ),
                    ),
                    true,
                    WarpScannerModeAutomatic,
                )
            }

            AWG -> {
                coordinator.upsertAwg(
                    preparation,
                    AwgProfileEntity("awg", "AWG", "{}", 42L),
                    AwgSecrets("awg-private", "awg-psk"),
                )
            }
        }
    }

    suspend fun assertSaved(fixture: ProfileMutationTestFixture) {
        when (this) {
            RELAY -> {
                assertEquals("relay.example", fixture.relayProfiles.load("relay")?.server)
                assertEquals("fixture-relay-secret", fixture.relayCredentials.load("relay")?.trojanPassword)
                assertEquals("relay", fixture.settings.snapshot().relayProfileId)
                assertTrue(fixture.settings.snapshot().relayEnabled)
                assertEquals(Mode.Proxy.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            }

            XRAY -> {
                assertEquals("xray.example", fixture.xrayMetadata.load("xray")?.serverAddress)
                assertEquals("uuid", fixture.xraySecrets.load("xray")?.uuid)
                assertEquals("xray", fixture.xraySelection.current().activeProfileId)
                assertEquals(Mode.VPN.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            }

            WARP -> {
                assertEquals("WARP", fixture.warpProfiles.load("warp")?.displayName)
                assertEquals("fixture-warp-secret", fixture.warpCredentials.load("warp")?.accessToken)
                assertEquals("warp.example", fixture.warpEndpoints.load("warp", GlobalWarpEndpointScopeKey)?.host)
                assertEquals("warp", fixture.warpProfiles.activeProfileId())
                assertEquals("warp", fixture.settings.snapshot().warpProfileId)
            }

            AWG -> {
                assertEquals(
                    AwgProfileEntity("awg", "AWG", "{}", 42L),
                    withContext(Dispatchers.IO) {
                        fixture.awgProfiles.getProfile("awg")
                    },
                )
                assertEquals(AwgSecrets("awg-private", "awg-psk"), fixture.awgCredentials.load("awg"))
            }
        }
    }
}

internal fun testXrayProfile() =
    XrayProfile(
        name = "Xray",
        outbound =
            XrayProfile.Outbound(
                serverAddress = "xray.example",
                serverPort = 443,
                uuid = "uuid",
                security = XrayProfile.Security.TLS,
                network = XrayProfile.Network.TCP,
                tls = XrayProfile.Tls(serverName = "xray.example"),
            ),
    )
