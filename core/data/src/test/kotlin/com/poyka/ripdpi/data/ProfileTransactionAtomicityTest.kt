package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
    fun `imported native records have one catalog entry and selector references remain`() =
        runTest {
            val fixture = fixture()
            fixture.initialize()
            AtomicProfileFamily.IMPORTED_RELAY.write(fixture)
            val imported = fixture.groups.list().single()
            for ((id, type) in listOf(
                "subscription" to ProxyGroupType.SUBSCRIPTION,
                "selector" to ProxyGroupType.BASIC,
            )) {
                fixture.groups.add(
                    imported.copy(
                        id = id,
                        type = type,
                        isSelector = true,
                        members = listOf((imported.members.single() as ProxyProfile.Trojan).copy(groupId = id)),
                        subscription =
                            if (type ==
                                ProxyGroupType.SUBSCRIPTION
                            ) {
                                Subscription(link = "https://sub.example")
                            } else {
                                null
                            },
                    ),
                )
            }
            val catalog =
                ProfileUtilityCatalogReader(
                    fixture.coordinator(),
                    fixture.stores,
                    fixture.pauseAuthority,
                    InlineDispatchers,
                ).observe()
                    .first()

            assertEquals(
                setOf(
                    ProfileUtilityReference.NativeRelay("imported-relay"),
                    ProfileUtilityReference.SelectorMember("subscription", "imported-relay"),
                    ProfileUtilityReference.SelectorMember("selector", "imported-relay"),
                ),
                catalog.entries.map { it.reference }.toSet(),
            )
            assertEquals(
                "Imported relay",
                catalog.entries.single { it.reference is ProfileUtilityReference.NativeRelay }.label,
            )
        }

    @Test
    fun `newer stop rejects a stale import without changing database state`() =
        runTest {
            val fixture = fixture()
            fixture.initialize()
            AtomicProfileFamily.IMPORTED_RELAY.write(fixture)
            val coordinator = fixture.coordinator()
            val preparation = coordinator.captureMutation(ProfileMutationOrigin.ExplicitActivation)
            fixture.pauseAuthority.reserveStop()
            val before = fixture.snapshot()
            val member =
                ProxyProfile.Trojan(
                    id = "stale-relay",
                    displayName = "Stale relay",
                    groupId = "stale-group",
                    server = "stale.example",
                    serverPort = 443,
                    password = "fixture-stale-secret",
                )

            val outcome =
                coordinator.upsertRelay(
                    preparation,
                    RelayProfileRecord("stale-relay", RelayKindTrojan, server = "stale.example"),
                    RelayCredentialRecord("stale-relay", trojanPassword = "fixture-stale-secret"),
                    enabled = true,
                    select = true,
                    importedGroup =
                        ProxyGroup(
                            id = "stale-group",
                            name = "Stale relay",
                            type = ProxyGroupType.BASIC,
                            order = 0,
                            isSelector = false,
                            members = listOf(member),
                        ),
                )

            assertEquals(ProfileMutationOutcome.Superseded, outcome)
            assertEquals(before, fixture.snapshot())
            fixture.reopen()
            assertEquals(before, fixture.snapshot())
        }

    @Test
    fun `import-only save after newer stop preserves settings provider and runtime authority`() =
        runTest {
            val fixture = fixture()
            fixture.initialize()
            fixture.settings.update {
                setRelayProfileId("prior")
                setRelayEnabled(true)
            }
            val provider = XrayProviderSelectionRecord("xray", "prior-xray")
            fixture.xraySelection.update(provider)
            val coordinator = fixture.coordinator()
            val preparation = coordinator.captureMutation(ProfileMutationOrigin.ImportOnly)
            fixture.pauseAuthority.reserveStop()
            val settings = fixture.settings.snapshot()
            val authority = fixture.pauseAuthority.snapshotAuthority()
            val member = ProxyProfile.Trojan("saved", "Saved", "saved-group", "saved.example", 443, "fixture")
            val mapped = requireNotNull(mapRelayProfile(member))

            val outcome = coordinator.upsertRelay(
                preparation,
                mapped.profile,
                mapped.credentials,
                enabled = false,
                select = false,
                expectedState = ExpectedRelayProfileState(null, null),
                importedGroup = ProxyGroup(
                    id = member.groupId,
                    name = member.displayName,
                    type = ProxyGroupType.BASIC,
                    order = 0,
                    isSelector = false,
                    members = listOf(member),
                ),
            )

            assertEquals(ProfileMutationOutcome.NonSuperseding, outcome)
            fixture.reopen()
            assertEquals(settings, fixture.settings.snapshot())
            assertEquals(provider, fixture.xraySelection.current())
            assertEquals(authority, fixture.pauseAuthority.snapshotAuthority())
            assertEquals(member, fixture.groups.list().single().members.single())
            assertEquals(mapped.profile, fixture.relayProfiles.load(member.id))
            assertEquals(mapped.credentials, fixture.relayCredentials.load(member.id))
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
    IMPORTED_RELAY("relay_credentials"),
    XRAY("xray_metadata"),
    WARP("warp_endpoints"),
    AWG("awg_credentials"),
    ;

    suspend fun write(fixture: ProfileMutationTestFixture) {
        val coordinator = fixture.coordinator()
        val preparation =
            coordinator.captureMutation(
                if (this ==
                    IMPORTED_RELAY
                ) {
                    ProfileMutationOrigin.ImportOnly
                } else {
                    ProfileMutationOrigin.SavedEdit
                },
            )
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

            IMPORTED_RELAY -> {
                val member =
                    ProxyProfile.Trojan(
                        id = "imported-relay",
                        displayName = "Imported relay",
                        groupId = "imported-group",
                        server = "imported.example",
                        serverPort = 443,
                        password = "fixture-imported-secret",
                    )
                coordinator.upsertRelay(
                    preparation,
                    RelayProfileRecord(
                        "imported-relay",
                        RelayKindTrojan,
                        operatorName = "Imported relay",
                        server = "imported.example",
                    ),
                    RelayCredentialRecord("imported-relay", trojanPassword = "fixture-imported-secret"),
                    false,
                    false,
                    importedGroup =
                        ProxyGroup(
                            id = "imported-group",
                            name = "Imported relay",
                            type = ProxyGroupType.BASIC,
                            order = 99,
                            isSelector = false,
                            members = listOf(member),
                        ),
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

            IMPORTED_RELAY -> {
                val group = fixture.groups.list().single()
                assertEquals("Imported relay", group.name)
                assertEquals(0, group.order)
                val member = group.members.single() as ProxyProfile.Trojan
                assertEquals("imported-group", member.groupId)
                assertEquals("fixture-imported-secret", member.password)
                assertEquals("imported.example", fixture.relayProfiles.load(member.id)?.server)
                assertEquals("Imported relay", fixture.relayProfiles.load(member.id)?.operatorName)
                assertEquals("fixture-imported-secret", fixture.relayCredentials.load(member.id)?.trojanPassword)
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
