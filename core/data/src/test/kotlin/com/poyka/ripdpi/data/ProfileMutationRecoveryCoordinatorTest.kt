package com.poyka.ripdpi.data

import app.cash.turbine.test
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.AwgBackupProfile
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileRecordPair
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileMutationRecoveryCoordinatorTest {
    @Test
    fun `automatic Warp provisioning retains the captured relay catalog generation`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
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
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val repository = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, coordinator)
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
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val repository = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, coordinator)
            val member = ProxyProfile.Vless("same", "member", "group", "fixture.example", 443, "fixture")
            val group = ProxyGroup("group", "group", ProxyGroupType.BASIC, 0, true, members = listOf(member))
            repository.add(group)
            val reference = ProfileUtilityReference.SelectorMember("group", "same")
            val before = checkNotNull(fixture.pauseAuthority.states.value)
            assertEquals(setOf(reference), before.profileUtility.catalog)
            fixture.pauseAuthority.profileUtility.setFavorite(reference, true)
            fixture.failGroupWrite = true
            assertTrue(runCatching { repository.update(group.copy(members = emptyList())) }.isFailure)
            assertFalse(checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogReady)
            fixture.failGroupWrite = false
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
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val repository = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, coordinator)
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
            val fixture = ProfileMutationTestFixture()
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
    fun `mutation generation advances only after durable completion and successful recovery or reset`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            fixture.journal.beforeComplete = { assertEquals(0L, fixture.mutationGeneration.generation.value) }
            fixture.coordinator().selectNativeProvider(
                fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation),
                XrayProviderSelectionRecord(),
                Mode.Proxy.preferenceValue,
            )
            assertEquals(1L, fixture.mutationGeneration.generation.value)
            fixture.journal.beforeComplete = null
            fixture.coordinator().recover()
            assertEquals(1L, fixture.mutationGeneration.generation.value)
            fixture.coordinator().runReset { }
            assertEquals(2L, fixture.mutationGeneration.generation.value)
            runCatching { fixture.coordinator().runReset { error("reset failed") } }
            assertEquals(2L, fixture.mutationGeneration.generation.value)
        }

    @Test
    fun `deleting active AWG profile clears its boot pointer`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val profile = AwgProfileEntity(id = "awg-active", name = "AWG", requestJson = "{}", updatedAt = 1L)
            fixture.awgProfiles.upsertProfile(profile)
            fixture.awgCredentials.save(profile.id, AwgSecrets(privateKey = "private"))
            fixture.bootSession.setActiveAwgProfileId(profile.id)

            fixture.coordinator().deleteAwg(
                fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitDeletion),
                profile.id,
            )

            assertNull(fixture.bootSession.activeAwgProfileId())
        }

    @Test
    fun `deleting another AWG profile preserves the active boot pointer`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val profile = AwgProfileEntity(id = "awg-unused", name = "AWG", requestJson = "{}", updatedAt = 1L)
            fixture.awgProfiles.upsertProfile(profile)
            fixture.bootSession.setActiveAwgProfileId("awg-active")

            fixture.coordinator().deleteAwg(
                fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitDeletion),
                profile.id,
            )

            assertEquals("awg-active", fixture.bootSession.activeAwgProfileId())
        }

    @Test
    fun `FULL private backup replacement clears stale AWG boot pointer`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            fixture.bootSession.setActiveAwgProfileId("awg-before-restore")

            fixture.coordinator().replacePrivateBackup(
                fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.RestoreProfiles),
                BackupPrivateDataV1(),
            )

            assertNull(fixture.bootSession.activeAwgProfileId())
        }

    @Test
    fun `awg secrets and metadata converge after interrupted upsert`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val profile = AwgProfileEntity(id = "awg-1", name = "AWG", requestJson = "{}", updatedAt = 1L)
            val secrets = AwgSecrets(privateKey = "private", presharedKey = "psk")
            fixture.awgProfiles.failNextUpsert = true

            val failure =
                runCatching {
                    fixture.coordinator().upsertAwg(
                        fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        profile,
                        secrets,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(secrets, fixture.awgCredentials.load(profile.id))
            assertNull(fixture.awgProfiles.getProfile(profile.id))
            assertTrue(fixture.journal.pending() != null)

            fixture.coordinator().recover()
            fixture.coordinator().recover()

            assertEquals(profile, fixture.awgProfiles.getProfile(profile.id))
            assertEquals(secrets, fixture.awgCredentials.load(profile.id))
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `relay after-image survives failure and replays after process restart`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val profile = RelayProfileRecord(id = "relay-1", kind = RelayKindVlessReality, server = "relay.example")
            val credentials = RelayCredentialRecord(profileId = profile.id, vlessUuid = "uuid")
            fixture.relayCredentials.failNextSave = true

            val failure =
                runCatching {
                    fixture.coordinator().upsertRelay(
                        fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        profile,
                        credentials,
                        enabled = true,
                        select = true,
                    )
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(0L, fixture.mutationGeneration.generation.value)
            assertEquals(profile, fixture.relayProfiles.load(profile.id))
            assertNull(fixture.relayCredentials.load(profile.id))
            assertEquals(ProfileMutationFamily.Relay, fixture.journal.pending()?.family)

            fixture.coordinator().recover()

            assertEquals(credentials, fixture.relayCredentials.load(profile.id))
            assertEquals(profile.id, fixture.settings.snapshot().relayProfileId)
            assertTrue(fixture.settings.snapshot().relayEnabled)
            assertNull(fixture.journal.pending())
            val savesAfterReplay = fixture.relayProfiles.saveCount
            assertEquals(1L, fixture.mutationGeneration.generation.value)

            fixture.coordinator().recover()

            assertEquals(savesAfterReplay, fixture.relayProfiles.saveCount)
            assertEquals(1L, fixture.mutationGeneration.generation.value)
        }

    @Test
    fun `conditional relay upsert rejects a replaced profile and credentials`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
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
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `native relay after-image recovers xray provider selection and mode after interrupted switch`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val profile = RelayProfileRecord(id = "relay-native", kind = RelayKindTrojan, server = "relay.example")
            val credentials = RelayCredentialRecord(profileId = profile.id, trojanPassword = "secret")
            val nativeSelection =
                XrayProviderSelectionRecord(providerKind = XrayProviderSelectionRecord.ProviderKindNative)
            fixture.xraySelection.update(
                XrayProviderSelectionRecord(
                    providerKind = XrayProviderSelectionRecord.ProviderKindXray,
                    activeProfileId = "xray-default",
                ),
            )
            fixture.xraySelection.failNextUpdate = true

            val failure =
                runCatching {
                    fixture.coordinator().upsertRelay(
                        fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        profile = profile,
                        credentials = credentials,
                        enabled = true,
                        select = true,
                        modeAfterImage = Mode.Proxy.preferenceValue,
                        xraySelectionAfterImage = nativeSelection,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(fixture.journal.pending() != null)
            assertEquals(XrayProviderSelectionRecord.ProviderKindXray, fixture.xraySelection.current().providerKind)
            assertFalse(fixture.settings.snapshot().relayEnabled)

            fixture.coordinator().recover()

            assertEquals(nativeSelection, fixture.xraySelection.current())
            assertEquals(Mode.Proxy.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            assertTrue(fixture.settings.snapshot().relayEnabled)
            assertEquals(profile, fixture.relayProfiles.load(profile.id))
            assertEquals(credentials, fixture.relayCredentials.load(profile.id))
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `xray provider after-image recovers profile selection and app settings after interrupted save`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val profile = testXrayProfile()
            fixture.xrayMetadata.failNextSave = true

            val failure =
                runCatching {
                    fixture.coordinator().upsertXrayProvider(
                        fixture.coordinator().captureMutation(
                            com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation,
                        ),
                        profileId = "xray-default",
                        profile = profile,
                        selection =
                            XrayProviderSelectionRecord(
                                providerKind = XrayProviderSelectionRecord.ProviderKindXray,
                                activeProfileId = "xray-default",
                            ),
                        modeAfterImage = Mode.VPN.preferenceValue,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertNull(fixture.xrayMetadata.load("xray-default"))
            assertTrue(fixture.xraySecrets.load("xray-default") != null)
            assertEquals(XrayProviderSelectionRecord(), fixture.xraySelection.current())
            assertEquals(ProfileMutationFamily.Xray, fixture.journal.pending()?.family)

            fixture.coordinator().recover()

            assertEquals("xray-default", fixture.xrayMetadata.load("xray-default")?.profileId)
            assertEquals("uuid", fixture.xraySecrets.load("xray-default")?.uuid)
            assertEquals(
                XrayProviderSelectionRecord(
                    providerKind = XrayProviderSelectionRecord.ProviderKindXray,
                    activeProfileId = "xray-default",
                ),
                fixture.xraySelection.current(),
            )
            assertEquals(Mode.VPN.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `warp delete clears selection before deleting all profile stores`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
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
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `warp after-image recovers endpoint and both active pointers`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val profile = WarpProfile(id = "warp-replay", displayName = "WARP", setupState = WarpSetupStateProvisioned)
            val credentials = WarpCredentials(profileId = profile.id, deviceId = "device", accessToken = "token")
            val endpoint =
                WarpEndpointCacheEntry(
                    profileId = profile.id,
                    networkScopeKey = GlobalWarpEndpointScopeKey,
                    host = "warp.example",
                    port = 2408,
                )
            fixture.warpEndpoints.failNextSave = true

            val failure =
                runCatching {
                    fixture.coordinator().upsertWarp(
                        fixture.coordinator().captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        profile = profile,
                        credentials = credentials,
                        endpoints = listOf(endpoint),
                        activate = true,
                        scannerMode = WarpScannerModeAutomatic,
                    )
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(fixture.journal.pending() != null)

            fixture.coordinator().recover()

            assertEquals(endpoint, fixture.warpEndpoints.load(profile.id, GlobalWarpEndpointScopeKey))
            assertEquals(profile.id, fixture.warpProfiles.activeProfileId())
            assertEquals(profile.id, fixture.settings.snapshot().warpProfileId)
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `corrupt pending intent blocks every recovered read until explicit reset`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            fixture.journal.prepare(
                PendingProfileMutation(
                    origin = com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit,
                    expectedPauseAuthority =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                    family = ProfileMutationFamily.Xray,
                    payload = "not-json",
                ),
            )
            repeat(2) {
                val failure =
                    runCatching {
                        coordinator.readRecovered {
                            error(
                                "partial state was exposed",
                            )
                        }
                    }.exceptionOrNull()
                assertTrue(failure is ProfileMutationJournalCorruptionException)
                assertTrue(fixture.journal.pending() != null)
            }
            val restarted = fixture.coordinator()
            assertTrue(
                runCatching { restarted.recover() }.exceptionOrNull() is ProfileMutationJournalCorruptionException,
            )

            restarted.runReset { }
            assertEquals("reset complete", restarted.readRecovered { "reset complete" })
        }

    @Test
    fun `unreadable journal marker remains pending across recovery retries`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            fixture.journal.prepare(
                PendingProfileMutation(
                    origin = com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit,
                    expectedPauseAuthority =
                        com.poyka.ripdpi.data
                            .PauseAuthorityRef(0),
                    family = ProfileMutationFamily.Xray,
                    payload = "not-json",
                ),
            )
            fixture.journal.pendingCorruptionFailuresRemaining = 2

            repeat(2) {
                val failure = runCatching { coordinator.recover() }.exceptionOrNull()
                assertTrue(failure is ProfileMutationJournalCorruptionException)
            }
            assertTrue(fixture.journal.pending() != null)
        }

    @Test
    fun `transient unreadable journal marker is preserved when retry can read it`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val profile = RelayProfileRecord(id = "relay-transient", server = "relay.example")
            val credentials = RelayCredentialRecord(profileId = profile.id, vlessUuid = "uuid")
            fixture.relayCredentials.failNextSave = true
            val interruptedMutation =
                runCatching {
                    coordinator.upsertRelay(
                        coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        profile,
                        credentials,
                        enabled = true,
                        select = true,
                    )
                }.exceptionOrNull()
            assertTrue(interruptedMutation != null)
            fixture.journal.pendingCorruptionFailuresRemaining = 1

            val failure = runCatching { coordinator.recover() }.exceptionOrNull()

            assertTrue(failure is ProfileMutationJournalCorruptionException)
            coordinator.recover()
            assertEquals(credentials, fixture.relayCredentials.load(profile.id))
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `private backup failure persists rollback intent rather than target`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val originalProfile = RelayProfileRecord(id = "relay-old", server = "old.example")
            val originalCredentials = RelayCredentialRecord(profileId = originalProfile.id, vlessUuid = "old-uuid")
            val originalAwg =
                AwgBackupProfile(
                    id = "awg-old",
                    name = "Old AWG",
                    requestJson = "{}",
                    updatedAt = 1L,
                )
            fixture.relayProfiles.save(originalProfile)
            fixture.relayCredentials.save(originalCredentials)
            fixture.awgProfiles.upsertProfile(originalAwg.toEntity())
            fixture.bootSession.setActiveAwgProfileId(originalAwg.id)
            val preimage =
                BackupPrivateDataV1(
                    relayProfiles = listOf(originalProfile),
                    relayCredentials = listOf(originalCredentials),
                    awgProfiles = listOf(originalAwg),
                )
            val targetProfile = RelayProfileRecord(id = "relay-new", server = "new.example")
            val targetCredentials = RelayCredentialRecord(profileId = targetProfile.id, vlessUuid = "new-uuid")
            val target =
                BackupPrivateDataV1(
                    relayProfiles = listOf(targetProfile),
                    relayCredentials = listOf(targetCredentials),
                )
            fixture.relayCredentials.failSaveAttempts = 2

            val failure =
                runCatching {
                    fixture.coordinator().replacePrivateBackup(
                        fixture.coordinator().captureMutation(
                            com.poyka.ripdpi.data.ProfileMutationOrigin.RestoreProfiles,
                        ),
                        target,
                        rollbackData = preimage,
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(1, failure?.suppressed?.size)
            assertTrue(fixture.journal.pending() != null)

            fixture.coordinator().recover()

            assertEquals(originalProfile, fixture.relayProfiles.load(originalProfile.id))
            assertEquals(originalCredentials, fixture.relayCredentials.load(originalProfile.id))
            assertNull(fixture.relayProfiles.load(targetProfile.id))
            assertEquals(originalAwg.id, fixture.bootSession.activeAwgProfileId())
            assertNull(fixture.journal.pending())
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileMutationRecoveryBarrierTest {
    @Test
    fun `reset barrier prevents a mutation from preparing until the wipe completes`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val resetStarted = CompletableDeferred<Unit>()
            val finishReset = CompletableDeferred<Unit>()
            val resetJob =
                launch {
                    coordinator.runReset {
                        resetStarted.complete(Unit)
                        finishReset.await()
                    }
                }
            resetStarted.await()
            val profile = AwgProfileEntity(id = "after-reset", name = "AWG", requestJson = "{}", updatedAt = 1L)
            val mutationJob =
                launch {
                    coordinator.upsertAwg(
                        coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        profile,
                        AwgSecrets(privateKey = "private"),
                    )
                }

            runCurrent()
            assertFalse(mutationJob.isCompleted)
            assertNull(fixture.journal.pending())

            finishReset.complete(Unit)
            resetJob.join()
            mutationJob.join()

            assertEquals(profile, fixture.awgProfiles.getProfile(profile.id))
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `recovered read finishes pending selection and excludes concurrent mutation`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val selection =
                XrayProviderSelectionRecord(
                    providerKind = XrayProviderSelectionRecord.ProviderKindXray,
                    activeProfileId = "xray-default",
                )
            fixture.xrayMetadata.failNextSave = true
            assertTrue(
                runCatching {
                    coordinator.upsertXrayProvider(
                        coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation),
                        "xray-default",
                        testXrayProfile(),
                        selection,
                        Mode.VPN.preferenceValue,
                    )
                }.isFailure,
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reader =
                launch {
                    coordinator.readRecovered {
                        assertEquals(selection, fixture.xraySelection.current())
                        assertEquals("xray-default", fixture.xrayMetadata.load("xray-default")?.profileId)
                        assertNull(fixture.journal.pending())
                        entered.complete(Unit)
                        release.await()
                        assertEquals(selection, fixture.xraySelection.current())
                    }
                }
            entered.await()
            val writer =
                launch {
                    coordinator.selectNativeProvider(
                        coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation),
                        XrayProviderSelectionRecord(),
                        Mode.Proxy.preferenceValue,
                    )
                }
            runCurrent()
            assertFalse(writer.isCompleted)
            release.complete(Unit)
            reader.join()
            writer.join()
            assertEquals(XrayProviderSelectionRecord(), fixture.xraySelection.current())
            assertEquals(Mode.Proxy.preferenceValue, fixture.settings.snapshot().ripdpiMode)
        }

    @Test
    fun `cancelled recovered read releases the mutation lock`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val entered = CompletableDeferred<Unit>()
            val reader =
                launch {
                    coordinator.readRecovered {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            entered.await()
            reader.cancelAndJoin()

            coordinator.selectNativeProvider(
                coordinator.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation),
                XrayProviderSelectionRecord(),
                Mode.Proxy.preferenceValue,
            )

            assertEquals(Mode.Proxy.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            assertNull(fixture.journal.pending())
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileMutationAuthorityRecoveryTest {
    @Test
    fun `legacy v1 replay completes before authority initialization`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val profile = AwgProfileEntity(id = "legacy", name = "Legacy", requestJson = "{}", updatedAt = 1)
            fixture.awgProfiles.upsertProfile(profile)
            fixture.journal.prepare(
                PendingProfileMutation(
                    schemaVersion = 1,
                    mutationId = "legacy-mutation",
                    family = ProfileMutationFamily.Awg,
                    payload = "{\"type\":\"awg_delete\",\"profileId\":\"legacy\"}",
                    origin = null,
                    expectedPauseAuthority = null,
                ),
            )
            fixture.journal.beforeComplete = { assertFalse(fixture.pauseAuthority.isInitialized()) }
            fixture.coordinator().recover()
            assertNull(fixture.awgProfiles.getProfile("legacy"))
            assertNull(fixture.journal.pending())
            assertTrue(fixture.pauseAuthority.isInitialized())
        }

    @Test
    fun `legacy marker after initialized authority is preserved and rejected`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            fixture.pauseAuthority.initializeAfterMigration()
            fixture.journal.prepare(
                PendingProfileMutation(
                    schemaVersion = 1,
                    mutationId = "legacy-mutation",
                    family = ProfileMutationFamily.Awg,
                    payload = "{\"type\":\"awg_delete\",\"profileId\":\"legacy\"}",
                    origin = null,
                    expectedPauseAuthority = null,
                ),
            )
            assertTrue(runCatching { fixture.coordinator().recover() }.isFailure)
            assertNotNull(fixture.journal.pending())
        }

    @Test
    fun `prepared activation never invalidates a newer pause or returns new dispatch authority`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val preparation = coordinator.captureMutation(ProfileMutationOrigin.ExplicitDeletion)
            val newer = fixture.pauseAuthority.begin(Mode.VPN, 300_000, fixture.pauseAuthority.snapshotAuthority())
            val result = coordinator.deleteAwg(preparation, "absent")
            assertEquals(ProfileMutationOutcome.Superseded, result)
            assertEquals(newer, fixture.pauseAuthority.snapshot())
        }

    @Test
    fun `v2 recovery repeats its original fence and preserves a newer pause`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            fixture.coordinator().recover()
            val old = fixture.pauseAuthority.reference()
            fixture.journal.prepare(
                PendingProfileMutation(
                    schemaVersion = 2,
                    mutationId = "prepared",
                    family = ProfileMutationFamily.Awg,
                    payload = "{\"type\":\"awg_delete\",\"profileId\":\"absent\"}",
                    origin = ProfileMutationOrigin.ExplicitDeletion,
                    expectedPauseAuthority = old,
                ),
            )
            val newer = fixture.pauseAuthority.begin(Mode.VPN, 300_000, fixture.pauseAuthority.snapshotAuthority())
            fixture.coordinator().recover()
            assertNull(fixture.journal.pending())
            assertEquals(newer, fixture.pauseAuthority.snapshot())
        }

    @Test
    fun `v2 without required provenance never replays or discards the marker`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            fixture.journal.prepare(
                PendingProfileMutation(
                    schemaVersion = 2,
                    mutationId = "missing-origin",
                    family = ProfileMutationFamily.Awg,
                    payload = "{\"type\":\"awg_delete\",\"profileId\":\"absent\"}",
                    origin = null,
                    expectedPauseAuthority = PauseAuthorityRef(0),
                ),
            )
            assertTrue(
                runCatching {
                    fixture.coordinator().recover()
                }.exceptionOrNull() is ProfileMutationJournalCorruptionException,
            )
            assertNotNull(fixture.journal.pending())
        }

    @Test
    fun `explicit Reset reserves stopped before discarding corrupt initialized journal`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            fixture.coordinator().recover()
            val pause = fixture.pauseAuthority.begin(Mode.VPN, 300_000, fixture.pauseAuthority.snapshotAuthority())
            fixture.journal.pendingCorruptionFailuresRemaining = 10
            fixture.journal.prepare(
                PendingProfileMutation(
                    schemaVersion = 2,
                    mutationId = "corrupt",
                    family = ProfileMutationFamily.Awg,
                    payload = "not-json",
                    origin = null,
                    expectedPauseAuthority = null,
                ),
            )
            var wiped = false
            fixture.journal.beforeClear = {
                assertNull(fixture.pauseAuthority.snapshot())
                assertTrue(fixture.pauseAuthority.reference().generation > pause.generation)
                assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
            }
            fixture.coordinator().runReset { wiped = true }
            assertTrue(wiped)
            fixture.journal.pendingCorruptionFailuresRemaining = 0
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `explicit legacy Reset establishes stopped without replay and clear failure skips wipe`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            fixture.journal.prepare(
                PendingProfileMutation(
                    schemaVersion = 1,
                    mutationId = "corrupt-legacy",
                    family = ProfileMutationFamily.Awg,
                    payload = "not-json",
                    origin = null,
                    expectedPauseAuthority = null,
                ),
            )
            var wiped = false
            fixture.journal.beforeClear =
                { assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired) }
            fixture.journal.clearFailure = IllegalStateException("clear failed")
            assertTrue(runCatching { fixture.coordinator().runReset { wiped = true } }.isFailure)
            assertFalse(wiped)
            assertNotNull(fixture.journal.pending())
            assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
            fixture.journal.clearFailure = null
            fixture.coordinator().runReset { wiped = true }
            assertTrue(wiped)
            assertNull(fixture.journal.pending())
        }
}

/** Transaction and unit crash-cut tests only: no native dispatch or network ACK is simulated. */
class MeasuredProfileSelectionTransactionTest {
    @Test
    fun `paused native measured selection keeps the reserved identity and stores selection before ACK`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            fixture.selectorLease()
            fixture.selectorChoice.commitMember("group", "member", SelectorChoiceOrigin.Reconstruction)
            val lease = fixture.nativeLease()
            val paused = checkNotNull(fixture.pauseAuthority.snapshot())
            val payload = lease.payload as ProfileUtilitySelectionPayload.Native
            var reservation: DurableCommandRecord? = null
            var pending: PendingProfileMutation? = null
            fixture.beforePrepare = {
                pending = it
                reservation = fixture.pauseAuthority.snapshotAuthority().command
                assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
                assertNull(fixture.pauseAuthority.snapshot())
                assertNoMeasuredHistory(fixture)
            }

            val result = fixture.coordinator().selectMeasuredProfile(lease, Mode.Proxy)
            assertTrue(result is ProfileUtilitySelectionResult.Selected)
            val selected = result as ProfileUtilitySelectionResult.Selected
            val reserved = checkNotNull(reservation)
            val state = checkNotNull(fixture.persistence.read())
            assertEquals(reserved, state.command)
            assertEquals(reserved.commandId, selected.receipt.commandId)
            assertEquals(PauseAuthorityRef(reserved.generation), selected.receipt.authority)
            assertEquals(reserved.origin, selected.receipt.origin)
            assertEquals(RuntimeCommandOrigin.MeasuredActivation(reserved.commandId, lease.reference), reserved.origin)
            assertEquals(selected.receipt.commandId, checkNotNull(pending).mutationId)
            assertEquals(selected.receipt.authority, checkNotNull(pending).expectedPauseAuthority)
            assertEquals(RuntimeActivationPhase.Unbound, reserved.phase)
            assertTrue(reserved.generation > paused.generation)
            assertEquals(state.profileUtility.catalogGeneration, selected.catalogGeneration)
            assertTrue(selected.catalogGeneration > lease.catalogGeneration)
            assertTrue(state.profileUtility.catalogReady)
            assertEquals(payload.profile, fixture.relayProfiles.load(payload.profile.id))
            assertEquals(payload.credentials, fixture.relayCredentials.load(payload.profile.id))
            assertEquals(payload.profile.id, fixture.settings.snapshot().relayProfileId)
            assertTrue(fixture.settings.snapshot().relayEnabled)
            assertEquals(Mode.Proxy.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            assertEquals(XrayProviderSelectionRecord(), fixture.xraySelection.current())
            assertNull(fixture.selectorChoice.activeGroupId)
            assertNull(fixture.journal.pending())
            assertEquals(DesiredRuntimeState.Stopped, state.desired)
            assertNull(state.pause)
            assertNoMeasuredHistory(fixture)

            // Binding alone is a unit authority operation, not dispatch or a native ACK.
            val activation = checkNotNull(fixture.pauseAuthority.bindProfileActivation(selected.receipt, Mode.Proxy))
            assertEquals(selected.receipt.commandId, activation.commandId)
            assertEquals(selected.receipt.origin, activation.origin)
            assertEquals(selected.receipt.authority, activation.authority)
            assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
            assertNoMeasuredHistory(fixture)
        }

    @Test
    fun `selector measured selection persists the exact member with the same receipt`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.selectorLease()
            val result = fixture.coordinator().selectMeasuredProfile(lease, Mode.VPN)
            assertTrue(result is ProfileUtilitySelectionResult.Selected)
            val selected = result as ProfileUtilitySelectionResult.Selected
            assertSame(selected.receipt, fixture.selectorChoice.manualReceipts["group"])
            assertEquals("group", fixture.selectorChoice.activeGroupId)
            assertEquals("member", fixture.selectorChoice.members["group"])
            assertEquals(
                RuntimeCommandOrigin.MeasuredActivation(selected.receipt.commandId, lease.reference),
                selected.receipt.origin,
            )
            assertEquals(Mode.VPN.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
            assertNull(fixture.pauseAuthority.snapshot())
            assertEquals(
                checkNotNull(fixture.persistence.read()).profileUtility.catalogGeneration,
                selected.catalogGeneration,
            )
            assertNoMeasuredHistory(fixture)
        }

    @Test
    fun `native payload with a same ID in the wrong typed namespace is superseded without mutation`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            fixture.xrayLease("relay")
            val native = fixture.nativeLease()
            val wrong = fixture.lease(ProfileUtilityReference.Xray("relay"), native.payload)
            assertMeasuredSupersededWithoutMutation(fixture, wrong)
        }

    @Test
    fun `same native ID with a changed endpoint supersedes the immutable lease`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.nativeLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Native
            fixture.relayProfiles.save(payload.profile.copy(server = "changed.example"))
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `same native ID with changed credentials supersedes the immutable lease`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.nativeLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Native
            fixture.relayCredentials.save(payload.credentials.copy(vlessUuid = "replacement-fixture"))
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `changed Xray metadata half supersedes the captured record pair`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.xrayLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Xray
            fixture.xrayMetadata.save(payload.records.metadata.copy(serverAddress = "changed.example"))
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `changed Xray secret half supersedes the captured record pair`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.xrayLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Xray
            fixture.xraySecrets.save(payload.records.secret.copy(uuid = "replacement-fixture"))
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `selector exact member changes supersede the captured payload`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.selectorLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Selector
            val member = payload.member as ProxyProfile.Vless
            fixture.writeGroups(
                listOf(
                    ProxyGroup(
                        "group",
                        "group",
                        ProxyGroupType.BASIC,
                        0,
                        true,
                        members = listOf(member.copy(server = "changed.example")),
                    ),
                ),
            )
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `selector group mismatch supersedes even when that other group contains the same member ID`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val first = fixture.selectorLease()
            val payload = first.payload as ProfileUtilitySelectionPayload.Selector
            fixture.writeGroups(
                listOf(
                    ProxyGroup("group", "group", ProxyGroupType.BASIC, 0, true, members = listOf(payload.member)),
                    ProxyGroup("other-group", "other", ProxyGroupType.BASIC, 1, true, members = listOf(payload.member)),
                ),
            )
            fixture.coordinator().recover()
            val lease =
                fixture.lease(
                    first.reference,
                    ProfileUtilitySelectionPayload.Selector("other-group", "member", payload.member),
                )
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `selector member ID mismatch supersedes even when both members exist`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val first = fixture.selectorLease()
            val payload = first.payload as ProfileUtilitySelectionPayload.Selector
            val other = (payload.member as ProxyProfile.Vless).copy(id = "other-member")
            fixture.writeGroups(
                listOf(
                    ProxyGroup(
                        "group",
                        "group",
                        ProxyGroupType.BASIC,
                        0,
                        true,
                        members = listOf(payload.member, other),
                    ),
                ),
            )
            fixture.coordinator().recover()
            val lease =
                fixture.lease(
                    first.reference,
                    ProfileUtilitySelectionPayload.Selector("group", "other-member", other),
                )
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `newer Pause between payload validation and reservation CAS preserves the exact new intent`() =
        runTest {
            assertNewIntentWinsMeasuredCas(pause = true)
        }

    @Test
    fun `newer Stop between payload validation and reservation CAS preserves the exact new intent`() =
        runTest {
            assertNewIntentWinsMeasuredCas(pause = false)
        }

    @Test
    fun `prepare failure cancels only its own unbound reservation and preserves the original failure`() =
        runTest {
            assertPrepareFailureOwnership(newerPause = null)
        }

    @Test
    fun `prepare failure compensation preserves a newer Pause command`() =
        runTest {
            assertPrepareFailureOwnership(newerPause = true)
        }

    @Test
    fun `prepare failure compensation preserves a newer Stop command`() =
        runTest {
            assertPrepareFailureOwnership(newerPause = false)
        }

    @Test
    fun `unit crash cut before prepare reconstructs stopped unbound authority with an empty journal`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.nativeLease()
            val storesBefore = fixture.measuredStores()
            val cut = captureMeasuredUnitCrashCut(fixture, lease, afterPrepare = false)
            assertNull(cut.pending)
            assertEquals(RuntimeActivationPhase.Unbound, cut.authority.command?.phase)
            assertEquals(DesiredRuntimeState.Stopped, cut.authority.desired)
            assertNull(cut.authority.pause)
            assertEquals(storesBefore, cut.stores)
            val recovered = reconstructMeasuredCut(cut)
            recovered.coordinator().recover()
            val state = checkNotNull(recovered.persistence.read())
            assertEquals(cut.authority, state)
            assertEquals(cut.stores, recovered.measuredStores())
            assertNull(recovered.journal.pending())
            assertEquals(0, recovered.prepareAttempts)
            assertEquals(0L, recovered.mutationGeneration.generation.value)
            assertNull(recovered.pauseAuthority.activationReceiptFromEnvelope(cut.envelope()))
            assertNoMeasuredHistory(recovered)
        }

    @Test
    fun `unit crash cut after prepared native journal finishes persistence and retires original capability`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.nativeLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Native
            val cut = captureMeasuredUnitCrashCut(fixture, lease, afterPrepare = true)
            val pending = checkNotNull(cut.pending)
            assertEquals(cut.authority.command?.commandId, pending.mutationId)
            assertEquals(cut.authority.command?.generation, pending.expectedPauseAuthority?.generation)
            val recorded = RipDpiContractJson.decodeFromString(ProfileMutationIntent.serializer(), pending.payload)
            assertTrue(recorded is MeasuredUtilitySelectionIntent)
            val measured = recorded as MeasuredUtilitySelectionIntent
            assertEquals(pending.mutationId, measured.reservation.commandId)
            assertEquals(pending.expectedPauseAuthority, measured.reservation.authority)
            assertEquals(Mode.Proxy.preferenceValue, measured.reservation.mode)
            val recovered = reconstructMeasuredCut(cut)
            val coordinator = recovered.coordinator()
            coordinator.recover()
            assertRetiredMeasuredCut(cut, recovered)
            assertEquals(payload.profile, recovered.relayProfiles.load(payload.profile.id))
            assertEquals(payload.credentials, recovered.relayCredentials.load(payload.profile.id))
            assertEquals(payload.profile.id, recovered.settings.snapshot().relayProfileId)
            assertTrue(recovered.settings.snapshot().relayEnabled)
            assertEquals(Mode.Proxy.preferenceValue, recovered.settings.snapshot().ripdpiMode)
            assertEquals(XrayProviderSelectionRecord(), recovered.xraySelection.current())
            assertEquals(1L, recovered.mutationGeneration.generation.value)
            val once = recovered.measuredCut()
            val saveCount = recovered.relayProfiles.saveCount
            coordinator.recover()
            assertEquals(once, recovered.measuredCut())
            assertEquals(saveCount, recovered.relayProfiles.saveCount)
            assertEquals(1L, recovered.mutationGeneration.generation.value)
        }

    @Test
    fun `unit crash cut after prepared selector journal reconstructs choice without a new manual receipt`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.selectorLease()
            val cut = captureMeasuredUnitCrashCut(fixture, lease, afterPrepare = true)
            val recovered = reconstructMeasuredCut(cut)
            val coordinator = recovered.coordinator()
            coordinator.recover()
            assertRetiredMeasuredCut(cut, recovered)
            assertEquals("group", recovered.selectorChoice.activeGroupId)
            assertEquals("member", recovered.selectorChoice.members["group"])
            assertTrue(recovered.selectorChoice.manualReceipts.isEmpty())
            val once = recovered.measuredCut()
            val writes = recovered.selectorChoice.writeCount
            coordinator.recover()
            assertEquals(once, recovered.measuredCut())
            assertEquals(writes, recovered.selectorChoice.writeCount)
        }
}

private class TestMeasuredSelectionLease(
    override val reference: ProfileUtilityReference,
    override val catalogGeneration: Long,
    override val expectedAuthority: RuntimeAuthoritySnapshot,
    override val payload: ProfileUtilitySelectionPayload,
) : ProfileUtilitySelectionLease {
    var beforeReservation: (() -> Unit)? = null
    var reservationChecks = 0

    override suspend fun refreshEnvironment() = true

    // Deliberately does not compare stores: the actual coordinator must reject stale exact payloads.
    override suspend fun payloadMatches() = true

    override fun environmentMatchesNow(): Boolean {
        reservationChecks += 1
        beforeReservation?.invoke()
        return true
    }

    override suspend fun refreshExternalEnvironment() = true

    override fun externalEnvironmentMatchesNow() = true
}

private suspend fun ProfileMutationTestFixture.nativeLease(): TestMeasuredSelectionLease {
    val profile = RelayProfileRecord(id = "relay", kind = RelayKindVlessReality, server = "measured.example")
    val credentials =
        RelayCredentialRecord(profileId = profile.id, vlessUuid = "opaque-fixture", updatedAtEpochMillis = 1)
    relayProfiles.save(profile)
    relayCredentials.save(credentials)
    coordinator().recover()
    pauseAuthority.begin(Mode.VPN, 300_000, pauseAuthority.snapshotAuthority())
    return lease(
        ProfileUtilityReference.NativeRelay(profile.id),
        ProfileUtilitySelectionPayload.Native(profile, credentials),
    )
}

private suspend fun ProfileMutationTestFixture.xrayLease(profileId: String = "xray"): TestMeasuredSelectionLease {
    val records =
        XrayProfileRecordPair(
            XrayProfileMetadataRecord(
                profileId = profileId,
                revision = "captured",
                serverAddress = "measured.example",
                updatedAtEpochMillis = 1,
            ),
            XrayProfileSecretRecord(profileId = profileId, revision = "captured", uuid = "opaque-fixture"),
        )
    xrayMetadata.save(records.metadata)
    xraySecrets.save(records.secret)
    coordinator().recover()
    pauseAuthority.begin(Mode.VPN, 300_000, pauseAuthority.snapshotAuthority())
    return lease(ProfileUtilityReference.Xray(profileId), ProfileUtilitySelectionPayload.Xray(profileId, records))
}

private suspend fun ProfileMutationTestFixture.selectorLease(): TestMeasuredSelectionLease {
    val member = ProxyProfile.Vless("member", "member", "group", "measured.example", 443, "opaque-fixture")
    writeGroups(listOf(ProxyGroup("group", "group", ProxyGroupType.BASIC, 0, true, members = listOf(member))))
    coordinator().recover()
    pauseAuthority.begin(Mode.VPN, 300_000, pauseAuthority.snapshotAuthority())
    return lease(
        ProfileUtilityReference.SelectorMember("group", "member"),
        ProfileUtilitySelectionPayload.Selector("group", "member", member),
    )
}

private fun ProfileMutationTestFixture.lease(
    reference: ProfileUtilityReference,
    payload: ProfileUtilitySelectionPayload,
): TestMeasuredSelectionLease {
    val utility = checkNotNull(pauseAuthority.states.value).profileUtility
    check(utility.catalogReady && reference in utility.catalog)
    return TestMeasuredSelectionLease(reference, utility.catalogGeneration, pauseAuthority.snapshotAuthority(), payload)
}

private fun ProfileMutationTestFixture.writeGroups(groups: List<ProxyGroup>) {
    groupBlob.write(RipDpiContractJson.encodeToString(ListSerializer(ProxyGroup.serializer()), groups))
}

private suspend fun assertMeasuredSupersededWithoutMutation(
    fixture: ProfileMutationTestFixture,
    lease: ProfileUtilitySelectionLease,
) {
    val before = fixture.measuredCut()
    val authorityWrites = fixture.persistence.commitCount
    val storeWrites = fixture.storeWriteAttempts
    val saves = fixture.relayProfiles.saveCount
    val selectorWrites = fixture.selectorChoice.writeCount
    val manual = fixture.selectorChoice.manualReceipts.toMap()
    val generation = fixture.mutationGeneration.generation.value
    assertEquals(
        ProfileUtilitySelectionResult.Superseded,
        fixture.coordinator().selectMeasuredProfile(lease, Mode.Proxy),
    )
    assertEquals(before, fixture.measuredCut())
    assertEquals(authorityWrites, fixture.persistence.commitCount)
    assertEquals(storeWrites, fixture.storeWriteAttempts)
    assertEquals(0, fixture.prepareAttempts)
    assertEquals(saves, fixture.relayProfiles.saveCount)
    assertEquals(selectorWrites, fixture.selectorChoice.writeCount)
    assertEquals(manual, fixture.selectorChoice.manualReceipts)
    assertEquals(generation, fixture.mutationGeneration.generation.value)
}

private suspend fun assertNewIntentWinsMeasuredCas(pause: Boolean) {
    val fixture = ProfileMutationTestFixture()
    val lease = fixture.nativeLease()
    val beforeStores = fixture.measuredStores()
    var newer: PauseAuthorityState? = null
    lease.beforeReservation = {
        if (pause) {
            fixture.pauseAuthority.begin(Mode.VPN, 900_000, fixture.pauseAuthority.snapshotAuthority())
        } else {
            fixture.pauseAuthority.reserveStop()
        }
        newer = fixture.persistence.read()
    }
    assertEquals(
        ProfileUtilitySelectionResult.Superseded,
        fixture.coordinator().selectMeasuredProfile(lease, Mode.Proxy),
    )
    assertEquals(1, lease.reservationChecks)
    val expected = checkNotNull(newer)
    assertEquals(expected, fixture.persistence.read())
    assertEquals(expected.command, fixture.pauseAuthority.snapshotAuthority().command)
    assertEquals(expected.pause, fixture.pauseAuthority.snapshot())
    assertEquals(beforeStores, fixture.measuredStores())
    assertEquals(0, fixture.storeWriteAttempts)
    assertEquals(0, fixture.prepareAttempts)
    assertNull(fixture.journal.pending())
    assertEquals(0L, fixture.mutationGeneration.generation.value)
    assertNoMeasuredHistory(fixture)
}

private suspend fun assertPrepareFailureOwnership(newerPause: Boolean?) {
    val fixture = ProfileMutationTestFixture()
    val lease = fixture.nativeLease()
    val storesBefore = fixture.measuredStores()
    val failure = IllegalStateException("prepare fixture failure")
    var reserved: PauseAuthorityState? = null
    var newer: PauseAuthorityState? = null
    fixture.beforePrepare = {
        reserved = fixture.persistence.read()
        if (newerPause != null) {
            if (newerPause) {
                fixture.pauseAuthority.begin(Mode.VPN, 900_000, fixture.pauseAuthority.snapshotAuthority())
            } else {
                fixture.pauseAuthority.reserveStop()
            }
            newer = fixture.persistence.read()
        }
        throw failure
    }
    val caught = runCatching { fixture.coordinator().selectMeasuredProfile(lease, Mode.Proxy) }.exceptionOrNull()
    assertSame(failure, caught)
    assertEquals(0, failure.suppressed.size)
    val original = checkNotNull(reserved)
    assertEquals(RuntimeActivationPhase.Unbound, original.command?.phase)
    if (newerPause == null) {
        assertEquals(
            original.copy(command = checkNotNull(original.command).copy(phase = RuntimeActivationPhase.Terminated)),
            fixture.persistence.read(),
        )
    } else {
        assertEquals(checkNotNull(newer), fixture.persistence.read())
    }
    assertEquals(storesBefore, fixture.measuredStores())
    assertEquals(0, fixture.storeWriteAttempts)
    assertNull(fixture.journal.pending())
    assertEquals(1, fixture.prepareAttempts)
    assertEquals(0L, fixture.mutationGeneration.generation.value)
    assertNoMeasuredHistory(fixture)
}

private fun assertNoMeasuredHistory(fixture: ProfileMutationTestFixture) {
    val utility = checkNotNull(fixture.persistence.read()).profileUtility
    assertTrue(utility.recents.isEmpty())
    assertTrue(utility.acknowledged.isEmpty())
    assertEquals(0L, utility.lastSequence)
}

private data class MeasuredStoreSnapshot(
    val settings: AppSettings,
    val relays: List<Pair<RelayProfileRecord, RelayCredentialRecord?>>,
    val xrays: List<Pair<XrayProfileMetadataRecord, XrayProfileSecretRecord?>>,
    val xraySelection: XrayProviderSelectionRecord,
    val groups: String?,
    val selectorGroup: String?,
    val selectorMembers: Map<String, String>,
)

private data class MeasuredDurableCut(
    val authority: PauseAuthorityState,
    val stores: MeasuredStoreSnapshot,
    val pending: PendingProfileMutation?,
) {
    fun envelope(): RuntimeActivationEnvelope {
        val command = checkNotNull(authority.command)
        return RuntimeActivationEnvelope(
            command.generation,
            command.commandId,
            command.origin,
            Mode.Proxy.preferenceValue,
        )
    }
}

private suspend fun ProfileMutationTestFixture.measuredStores() =
    MeasuredStoreSnapshot(
        settings.snapshot(),
        relayProfiles.list().map { it to relayCredentials.load(it.id) },
        xrayMetadata.list().map { it to xraySecrets.load(it.profileId) },
        xraySelection.current(),
        groupBlob.read(),
        selectorChoice.activeGroupId,
        selectorChoice.members.toMap(),
    )

private suspend fun ProfileMutationTestFixture.measuredCut() =
    MeasuredDurableCut(checkNotNull(persistence.read()), measuredStores(), journal.pending())

/**
 * Captures actual persisted state and abandons an unresumed continuation at the journal boundary.
 * No Error, cancellation, catch, or finally executes in the original transaction after the cut.
 * This models a unit crash cut only, not Android process death or filesystem durability.
 */
private fun captureMeasuredUnitCrashCut(
    fixture: ProfileMutationTestFixture,
    lease: ProfileUtilitySelectionLease,
    afterPrepare: Boolean,
): MeasuredDurableCut {
    var captured: MeasuredDurableCut? = null
    var completed = false
    val cut: suspend (PendingProfileMutation) -> Unit = {
        captured = fixture.measuredCut()
        suspendCoroutine<Unit> { /* The continuation is intentionally never resumed. */ }
    }
    if (afterPrepare) fixture.afterPrepare = cut else fixture.beforePrepare = cut
    val transaction: suspend () -> Unit = { fixture.coordinator().selectMeasuredProfile(lease, Mode.Proxy) }
    transaction.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                completed = true
                result.getOrThrow()
            }
        },
    )
    check(!completed) { "Transaction crossed the intended unit crash cut" }
    val snapshot = checkNotNull(captured) { "Transaction never reached the intended unit crash cut" }
    assertEquals(snapshot.authority, fixture.persistence.read())
    return snapshot
}

private suspend fun reconstructMeasuredCut(cut: MeasuredDurableCut): ProfileMutationTestFixture {
    val fixture = ProfileMutationTestFixture(cut.authority)
    fixture.settings.replace(cut.stores.settings)
    for ((profile, credentials) in cut.stores.relays) {
        fixture.relayProfiles.save(profile)
        credentials?.let { fixture.relayCredentials.save(it) }
    }
    for ((metadata, secret) in cut.stores.xrays) {
        fixture.xrayMetadata.save(metadata)
        secret?.let { fixture.xraySecrets.save(it) }
    }
    fixture.xraySelection.update(cut.stores.xraySelection)
    cut.stores.groups?.let { fixture.groupBlob.write(it) }
    for ((group, member) in cut.stores.selectorMembers) {
        fixture.selectorChoice.commitMember(group, member, SelectorChoiceOrigin.Reconstruction)
    }
    if (cut.stores.selectorGroup == null) {
        fixture.selectorChoice.clearStandalone()
    } else {
        cut.stores.selectorMembers[cut.stores.selectorGroup]?.let {
            fixture.selectorChoice.commitMember(cut.stores.selectorGroup, it, SelectorChoiceOrigin.Reconstruction)
        }
    }
    cut.pending?.let { fixture.journal.prepare(it) }
    return fixture
}

private suspend fun assertRetiredMeasuredCut(
    cut: MeasuredDurableCut,
    fixture: ProfileMutationTestFixture,
) {
    val before = checkNotNull(cut.authority.command)
    val after = checkNotNull(fixture.pauseAuthority.snapshotAuthority().command)
    assertEquals(before.copy(phase = RuntimeActivationPhase.Terminated), after)
    assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
    assertNull(fixture.pauseAuthority.snapshot())
    assertEquals(cut.authority.generation, fixture.pauseAuthority.reference().generation)
    assertTrue(checkNotNull(fixture.persistence.read()).profileUtility.catalogReady)
    assertTrue(
        checkNotNull(fixture.persistence.read()).profileUtility.catalogGeneration >
            cut.authority.profileUtility.catalogGeneration,
    )
    assertNull(fixture.pauseAuthority.activationReceiptFromEnvelope(cut.envelope()))
    assertNull(fixture.journal.pending())
    assertEquals(0, fixture.prepareAttempts)
    assertTrue(fixture.selectorChoice.manualReceipts.isEmpty())
    assertNoMeasuredHistory(fixture)
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

class MeasuredProviderRecoveryTest {
    @Test fun `prepared measured selector journal cannot replace a newer Stop provider`() =
        runTest {
            assertNewCommandPreserved(start = false)
        }

    @Test fun `prepared measured selector journal cannot replace a newer Start provider`() =
        runTest {
            assertNewCommandPreserved(start = true)
        }

    @Test fun `prepared measured selector journal finishes original metadata without native capability or history`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.selectorLease()
            val cut = captureMeasuredUnitCrashCut(fixture, lease, afterPrepare = true)
            val recovered = reconstructMeasuredCut(cut)
            recovered.coordinator().recover()
            assertRetiredMeasuredCut(cut, recovered)
            assertEquals("group", recovered.selectorChoice.activeGroupId)
            assertEquals("member", recovered.selectorChoice.members["group"])
            assertNull(recovered.selectorChoice.manualReceipts["group"])
            assertNull(recovered.pauseAuthority.activationReceiptFromEnvelope(cut.envelope()))
            assertNoMeasuredHistory(recovered)
        }

    @Test fun `ordinary measured Native selection cannot overwrite a Stop committed during secret store await`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.nativeLease()
            val newer = XrayProviderSelectionRecord.of(com.poyka.ripdpi.data.xray.VpnProviderKind.Xray, "newer")
            fixture.beforeCredentialSave = {
                fixture.beforeCredentialSave = null
                fixture.pauseAuthority.reserveStop()
                fixture.xraySelection.update(newer)
                fixture.settings.update { setRelayProfileId("newer-native") }
            }
            val result = fixture.coordinator().selectMeasuredProfile(lease, Mode.Proxy)
            assertEquals(newer, fixture.xraySelection.current())
            assertEquals("newer-native", fixture.settings.snapshot().relayProfileId)
            assertEquals(ProfileUtilitySelectionResult.Superseded, result)
            assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
            assertNoMeasuredHistory(fixture)
        }

    @Test fun `ordinary measured Xray selection cannot overwrite Stop settings after the settings store await`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val lease = fixture.xrayLease()
            val newer = XrayProviderSelectionRecord.of(com.poyka.ripdpi.data.xray.VpnProviderKind.Xray, "newer")
            fixture.beforeSettingsUpdate = {
                fixture.beforeSettingsUpdate = null
                fixture.pauseAuthority.reserveStop()
                fixture.xraySelection.update(newer)
                fixture.settings.update { setRipdpiMode(Mode.Proxy.preferenceValue) }
            }
            val result = fixture.coordinator().selectMeasuredProfile(lease, Mode.VPN)
            assertEquals(newer, fixture.xraySelection.current())
            assertEquals(Mode.Proxy.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            assertEquals(ProfileUtilitySelectionResult.Superseded, result)
            assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
            assertNoMeasuredHistory(fixture)
        }

    private suspend fun assertNewCommandPreserved(start: Boolean) {
        val fixture = ProfileMutationTestFixture()
        val lease = fixture.selectorLease()
        val cut = captureMeasuredUnitCrashCut(fixture, lease, afterPrepare = true)
        val recovered = reconstructMeasuredCut(cut)
        recovered.pauseAuthority.initializeAfterMigration()
        if (start) recovered.pauseAuthority.reserveStart(Mode.VPN) else recovered.pauseAuthority.reserveStop()
        val authority = recovered.pauseAuthority.snapshotAuthority()
        val selected = XrayProviderSelectionRecord.of(com.poyka.ripdpi.data.xray.VpnProviderKind.Xray, "newer")
        recovered.xraySelection.update(selected)
        recovered.warpProfiles.setActiveProfileId("newer-warp")
        recovered.bootSession.setActiveAwgProfileId("newer-awg")
        recovered.coordinator().recover()
        assertEquals(authority, recovered.pauseAuthority.snapshotAuthority())
        assertEquals(selected, recovered.xraySelection.current())
        assertEquals("newer-warp", recovered.warpProfiles.activeProfileId())
        assertEquals("newer-awg", recovered.bootSession.activeAwgProfileId())
        assertNull(recovered.selectorChoice.activeGroupId)
        assertNull(recovered.selectorChoice.manualReceipts["group"])
        assertNull(recovered.journal.pending())
        assertNoMeasuredHistory(recovered)
    }
}

private fun testXrayProfile(): XrayProfile =
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

/** Real journal/recovery transaction with controlled stores, shared by selector transition tests. */
internal suspend fun selectorTransitionCoordinator(
    authority: PauseIntentAuthority,
    choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
    provider: com.poyka.ripdpi.data.xray.XrayProviderSelectionStore,
    settings: AppSettingsRepository,
    warpProfiles: WarpProfileStore,
): ProfileMutationRecoveryCoordinator {
    val fixture = ProfileMutationTestFixture()
    fixture.selectorLease()
    val original = fixture.stores

    val stores =
        ProfileMutationStores(
            settings,
            original.relayProfiles,
            original.relayCredentials,
            warpProfiles,
            original.warpCredentials,
            original.warpEndpoints,
            original.xrayMetadata,
            original.xraySecrets,
            provider,
            original.bootSession,
            original.groupCatalog,
            choice,
        )
    return ProfileMutationRecoveryCoordinator(
        stores,
        fixture.awgProfiles,
        fixture.awgCredentials,
        fixture.journal,
        fixture.mutationGeneration,
        authority,
    )
}
