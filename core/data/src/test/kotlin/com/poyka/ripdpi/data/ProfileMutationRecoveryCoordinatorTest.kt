package com.poyka.ripdpi.data

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileMutationRecoveryCoordinatorTest : RoomProfileTest() {
    @Test
    fun `automatic Warp provisioning retains the captured relay catalog generation`() =
        runTest {
            val fixture = fixture()
            val coordinator = fixture.coordinator()
            val profile = WarpProfile(id = "warp", displayName = "Warp")
            val credentials =
                WarpCredentials(profileId = profile.id, deviceId = "device", accessToken = "captured-fixture")
            coordinator.upsertWarp(
                coordinator.captureMutation(ProfileMutationOrigin.Bootstrap),
                profile,
                credentials,
                emptyList(),
                true,
                WarpScannerModeAutomatic,
            )
            val captured = checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogGeneration
            val activation = fixture.pauseAuthority.reserveStart(Mode.VPN)
            val identity = RuntimeAppliedUseIdentity("warp-runtime", 1, Mode.VPN.preferenceValue)
            assertTrue(fixture.pauseAuthority.claimActivation(activation, identity))
            val original = RuntimeAppliedIntent.Activation(activation)
            assertTrue(
                coordinator.upsertWarpForRuntimeProvisioning(
                    profile,
                    credentials.copy(accessToken = "issued-fixture"),
                    emptyList(),
                    false,
                    WarpScannerModeAutomatic,
                    credentials,
                    coordinator.warpRuntimeRevision(profile.id),
                ),
            )
            assertEquals(captured, checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogGeneration)
            assertTrue(
                fixture.pauseAuthority.acknowledgeApplied(
                    original,
                    RuntimeAppliedUseReceipt(
                        identity,
                        emptyList(),
                        1_000,
                        captured,
                        false,
                        "0".repeat(64),
                    ),
                ),
            )
        }

    @Test
    fun `catalog observer reloads committed metadata and favorites never rearm its reads`() =
        runTest {
            val fixture = fixture()
            val coordinator = fixture.coordinator()
            val repository = DefaultProxyGroupRepository(fixture.groupCatalog, coordinator)
            val reader =
                ProfileUtilityCatalogReader(coordinator, fixture.stores, fixture.pauseAuthority, InlineDispatchers)
            reader.observe().test {
                assertTrue(awaitItem().entries.isEmpty())
                val member = ProxyProfile.Vless("member", "display", "group", "fixture.example", 443, "fixture")
                repository.add(ProxyGroup("group", "group", ProxyGroupType.BASIC, 0, true, members = listOf(member)))
                val added = awaitItem()
                assertEquals("display", added.entries.single().label)
                val reference = ProfileUtilityReference.SelectorMember("group", "member")
                assertEquals(reference, added.entries.single().reference)
                fixture.pauseAuthority.profileUtility.setFavorite(reference, true)
                runCurrent()
                expectNoEvents()
                repository.delete(coordinator.captureMutation(ProfileMutationOrigin.ExplicitDeletion), "group")
                assertTrue(awaitItem().entries.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `actual group writes publish committed catalog and failed write recovers without invented members`() =
        runTest {
            val fixture = fixture()
            val coordinator = fixture.coordinator()
            val repository = DefaultProxyGroupRepository(fixture.groupCatalog, coordinator)
            val member = ProxyProfile.Vless("same", "member", "group", "fixture.example", 443, "fixture")
            val group = ProxyGroup("group", "group", ProxyGroupType.BASIC, 0, true, members = listOf(member))
            repository.add(group)
            val reference = ProfileUtilityReference.SelectorMember("group", "same")
            val before = checkNotNull(fixture.pauseAuthority.states.value)
            assertEquals(setOf(reference), before.profileUtility.catalog)
            fixture.pauseAuthority.profileUtility.setFavorite(reference, true)
            fixture.failWrite("proxy_groups")
            assertTrue(runCatching { repository.update(group.copy(members = emptyList())) }.isFailure)
            assertFalse(checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogReady)
            fixture.clearFaults()
            coordinator.recover()
            assertEquals(listOf(member), repository.list().single().members)
            assertEquals(setOf(reference), checkNotNull(fixture.pauseAuthority.states.value).profileUtility.favorites)
            repository.delete(coordinator.captureMutation(ProfileMutationOrigin.ExplicitDeletion), group.id)
            val deleted = checkNotNull(fixture.pauseAuthority.states.value).profileUtility
            assertTrue(deleted.catalogReady)
            assertTrue(deleted.catalog.isEmpty())
            assertTrue(deleted.favorites.isEmpty())
            assertTrue(deleted.catalogGeneration > before.profileUtility.catalogGeneration)
        }

    @Test
    fun `composite reset can replace groups without recursive mutex and prunes utility`() =
        runTest {
            val fixture = fixture()
            val coordinator = fixture.coordinator()
            val repository = DefaultProxyGroupRepository(fixture.groupCatalog, coordinator)
            val member = ProxyProfile.Vless("member", "member", "group", "fixture.example", 443, "fixture")
            repository.add(ProxyGroup("group", "group", ProxyGroupType.BASIC, 0, true, members = listOf(member)))
            coordinator.runReset { receipt -> repository.replaceAll(receipt, emptyList()) }
            assertTrue(repository.list().isEmpty())
            val state = checkNotNull(fixture.pauseAuthority.states.value)
            assertEquals(DesiredRuntimeState.Stopped, state.desired)
            assertTrue(state.profileUtility.catalogReady)
            assertTrue(state.profileUtility.catalog.isEmpty())
        }

    @Test
    fun `runtime provisioning is profile fenced rejects user ABA and preserves metadata and settings`() =
        runTest {
            val fixture = fixture()
            val coordinator = fixture.coordinator()
            val profile = WarpProfile(id = "warp-active", displayName = "original")
            val original =
                WarpCredentials(profileId = profile.id, deviceId = "device", accessToken = "original-fixture")
            coordinator.upsertWarp(
                coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                profile,
                original,
                emptyList(),
                true,
                WarpScannerModeAutomatic,
            )
            val capturedRevision = coordinator.warpRuntimeRevision(profile.id)
            coordinator.upsertWarp(
                coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                profile.copy(id = "unrelated"),
                original.copy(profileId = "unrelated"),
                emptyList(),
                false,
                WarpScannerModeAutomatic,
            )
            assertEquals(capturedRevision, coordinator.warpRuntimeRevision(profile.id))
            coordinator.upsertWarp(
                coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                profile.copy(displayName = "user name"),
                original.copy(displayName = "user name", license = "user-license"),
                emptyList(),
                false,
                WarpScannerModeAutomatic,
            )
            assertEquals(capturedRevision, coordinator.warpRuntimeRevision(profile.id))
            val settingsBefore = fixture.settings.snapshot()
            assertTrue(
                coordinator.upsertWarpForRuntimeProvisioning(
                    profile.copy(setupState = WarpSetupStateProvisioned),
                    original.copy(accessToken = "automatic-fixture"),
                    emptyList(),
                    false,
                    WarpScannerModeAutomatic,
                    original,
                    capturedRevision,
                ),
            )
            assertEquals("user name", fixture.warpProfiles.load(profile.id)?.displayName)
            assertEquals(profile.setupState, fixture.warpProfiles.load(profile.id)?.setupState)
            assertEquals("user-license", fixture.warpCredentials.load(profile.id)?.license)
            assertEquals(settingsBefore, fixture.settings.snapshot())
            assertStaleProvisioningAfterUserAba(fixture, coordinator, profile)
        }

    @Test
    fun `conditional relay upsert rejects a replaced profile and credentials`() =
        runTest {
            val fixture = fixture()
            val original = RelayProfileRecord(id = "relay-1", kind = RelayKindVlessReality, server = "old.example")
            val replacement = original.copy(server = "imported.example")
            val originalCredentials = RelayCredentialRecord(profileId = original.id, vlessUuid = "old-secret")
            val importedCredentials = originalCredentials.copy(vlessUuid = "imported-secret")
            fixture.relayProfiles.save(replacement)
            fixture.relayCredentials.save(importedCredentials)

            val failure =
                runCatching {
                    fixture.coordinator().upsertRelay(
                        fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        profile = original.copy(server = "edited.example"),
                        credentials = originalCredentials,
                        enabled = true,
                        select = true,
                        settingsAfterImage = fixture.settings.snapshot(),
                        expectedState = ExpectedRelayProfileState(original, originalCredentials),
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(replacement, fixture.relayProfiles.load(original.id))
            assertEquals(importedCredentials, fixture.relayCredentials.load(original.id))
        }

    @Test
    fun `warp delete clears selection before deleting all profile stores`() =
        runTest {
            val fixture = fixture()
            val profile = WarpProfile(id = "warp-1", displayName = "WARP")
            fixture.warpProfiles.save(profile)
            fixture.warpProfiles.setActiveProfileId(profile.id)
            fixture.warpCredentials.save(
                profile.id,
                WarpCredentials(profileId = profile.id, deviceId = "device", accessToken = "token"),
            )
            fixture.warpEndpoints.save(
                WarpEndpointCacheEntry(
                    profileId = profile.id,
                    networkScopeKey = GlobalWarpEndpointScopeKey,
                    host = "warp.example",
                    port = 2408,
                ),
            )
            fixture.settings.update { setWarpProfileId(profile.id) }

            fixture.coordinator().deleteWarp(
                fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitDeletion),
                profile.id,
                clearActive = true,
            )

            assertNull(fixture.warpProfiles.load(profile.id))
            assertNull(fixture.warpProfiles.activeProfileId())
            assertNull(fixture.warpCredentials.load(profile.id))
            assertTrue(fixture.warpEndpoints.loadAll(profile.id).isEmpty())
            assertFalse(fixture.settings.snapshot().warpProfileId == profile.id)
        }
}

private suspend fun assertStaleProvisioningAfterUserAba(
    fixture: ProfileMutationTestFixture,
    coordinator: ProfileMutationRecoveryCoordinator,
    profile: WarpProfile,
) {
    val updated = fixture.warpCredentials.load(profile.id)!!
    val updatedRevision = coordinator.warpRuntimeRevision(profile.id)
    coordinator.upsertWarp(
        coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
        profile,
        updated.copy(accessToken = "user-other-fixture"),
        emptyList(),
        false,
        WarpScannerModeAutomatic,
    )
    coordinator.upsertWarp(
        coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
        profile,
        updated,
        emptyList(),
        false,
        WarpScannerModeAutomatic,
    )
    assertFalse(
        coordinator.upsertWarpForRuntimeProvisioning(
            profile,
            updated.copy(accessToken = "late-automatic-fixture"),
            emptyList(),
            false,
            WarpScannerModeAutomatic,
            updated,
            updatedRevision,
        ),
    )
    assertEquals(updated, fixture.warpCredentials.load(profile.id))
}
