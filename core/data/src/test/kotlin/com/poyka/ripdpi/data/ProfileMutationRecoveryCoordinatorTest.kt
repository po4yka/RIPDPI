package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.AwgBackupProfile
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileMutationRecoveryCoordinatorTest {
    @Test
    fun `runtime provisioning is profile fenced rejects user ABA and preserves metadata and settings`() =
        runTest {
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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
            val fixture = Fixture()
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

private suspend fun assertStaleProvisioningAfterUserAba(
    fixture: Fixture,
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

private class Fixture {
    val pauseAuthority =
        PauseIntentAuthority(
            object : PauseAuthorityPersistence {
                private var state: PauseAuthorityState? = null

                override fun read() = state

                override fun commit(state: PauseAuthorityState) {
                    this.state = state
                }
            },
            object : PauseClock {
                override fun read() = PauseClockReading(1_800_000_000_000, 10_000, 7)
            },
            com.poyka.ripdpi.data
                .RuntimeIntentLinearizer(),
        )
    val mutationGeneration = ProfileMutationGenerationPublisher()
    val settings = InMemorySettingsRepository()
    val relayProfiles = InMemoryRelayProfileStore()
    val relayCredentials = InMemoryRelayCredentialStore()
    val warpProfiles = InMemoryWarpProfileStore()
    val warpCredentials = InMemoryWarpCredentialStore()
    val warpEndpoints = InMemoryWarpEndpointStore()
    val awgProfiles = InMemoryAwgProfileDao()
    val awgCredentials = InMemoryAwgCredentialStore()
    val xrayMetadata = InMemoryXrayMetadataStore()
    val xraySecrets = InMemoryXraySecretStore()
    val xraySelection = InMemoryXraySelectionStore()
    val journal = InMemoryProfileMutationJournal()
    val bootSession = InMemoryBootSessionStateStore()

    fun coordinator() =
        ProfileMutationRecoveryCoordinator(
            pauseAuthority = pauseAuthority,
            mutationGeneration = mutationGeneration,
            stores =
                ProfileMutationStores(
                    settings = settings,
                    relayProfiles = relayProfiles,
                    relayCredentials = relayCredentials,
                    warpProfiles = warpProfiles,
                    warpCredentials = warpCredentials,
                    warpEndpoints = warpEndpoints,
                    xrayMetadata = xrayMetadata,
                    xraySecrets = xraySecrets,
                    xraySelection = xraySelection,
                    bootSession = bootSession,
                ),
            awgProfiles = awgProfiles,
            awgCredentials = awgCredentials,
            journal = journal,
        )
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

private class InMemoryBootSessionStateStore : BootSessionStateStore {
    private var activeAwgProfileId: String? = null

    override fun lastSession(): BootSessionPointer? = null

    override fun recordSession(
        profileId: String,
        mode: Mode,
    ) = Unit

    override fun activeAwgProfileId(): String? = activeAwgProfileId

    override fun setActiveAwgProfileId(profileId: String?) {
        activeAwgProfileId = profileId
    }

    override fun clear() = Unit

    override fun wasRunningAtUpdate(): Boolean = false

    override fun setWasRunningAtUpdate(value: Boolean) = Unit
}

private class InMemoryProfileMutationJournal : ProfileMutationJournal {
    private var value: PendingProfileMutation? = null
    var pendingCorruptionFailuresRemaining = 0
    var beforeComplete: (() -> Unit)? = null
    var beforeClear: (() -> Unit)? = null
    var clearFailure: Throwable? = null

    override suspend fun prepare(mutation: PendingProfileMutation) {
        check(value == null)
        value = mutation
    }

    override suspend fun pending(): PendingProfileMutation? {
        if (pendingCorruptionFailuresRemaining > 0) {
            pendingCorruptionFailuresRemaining -= 1
            throw ProfileMutationJournalCorruptionException("corrupt marker")
        }
        return value
    }

    override suspend fun replace(
        expectedMutationId: String,
        mutation: PendingProfileMutation,
    ) {
        check(value?.mutationId == expectedMutationId)
        value = mutation
    }

    override suspend fun complete(mutationId: String) {
        beforeComplete?.invoke()
        check(value?.mutationId == mutationId)
        value = null
    }

    override suspend fun clearForReset() {
        beforeClear?.invoke()
        clearFailure?.let { throw it }
        value = null
    }
}

private class InMemorySettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)
    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class InMemoryRelayProfileStore : RelayProfileStore {
    private val values = mutableMapOf<String, RelayProfileRecord>()
    var saveCount = 0

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun list() = values.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        saveCount += 1
        values[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryRelayCredentialStore : RelayCredentialStore {
    private val values = mutableMapOf<String, RelayCredentialRecord>()
    var failNextSave = false
    var failSaveAttempts = 0

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        if (failNextSave || failSaveAttempts > 0) {
            failNextSave = false
            if (failSaveAttempts > 0) failSaveAttempts -= 1
            error("simulated credential store failure")
        }
        values[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryWarpProfileStore : WarpProfileStore {
    private val values = mutableMapOf<String, WarpProfile>()
    private var activeId: String? = null

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun loadAll() = values.values.toList()

    override suspend fun save(profile: WarpProfile) {
        values[profile.id] = profile
    }

    override suspend fun remove(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun activeProfileId() = activeId

    override suspend fun setActiveProfileId(profileId: String?) {
        activeId = profileId
    }

    override suspend fun clearAll() {
        values.clear()
        activeId = null
    }
}

private class InMemoryWarpCredentialStore : WarpCredentialStore {
    private val values = mutableMapOf<String, WarpCredentials>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun loadAll() = values.values.toList()

    override suspend fun save(
        profileId: String,
        credentials: WarpCredentials,
    ) {
        values[profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryWarpEndpointStore : WarpEndpointStore {
    private val values = mutableMapOf<Pair<String, String>, WarpEndpointCacheEntry>()
    var failNextSave = false

    override suspend fun load(
        profileId: String,
        networkScopeKey: String,
    ) = values[profileId to networkScopeKey]

    override suspend fun loadAll(profileId: String) = values.values.filter { it.profileId == profileId }

    override suspend fun save(entry: WarpEndpointCacheEntry) {
        if (failNextSave) {
            failNextSave = false
            error("simulated endpoint store failure")
        }
        values[entry.profileId to entry.networkScopeKey] = entry
    }

    override suspend fun clear(
        profileId: String,
        networkScopeKey: String,
    ) {
        values.remove(profileId to networkScopeKey)
    }

    override suspend fun clearProfile(profileId: String) {
        values.keys.removeAll { it.first == profileId }
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryAwgProfileDao : AwgProfileDao {
    private val values = mutableMapOf<String, AwgProfileEntity>()
    private val state = MutableStateFlow<List<AwgProfileEntity>>(emptyList())
    var failNextUpsert = false

    override fun observeProfiles(): Flow<List<AwgProfileEntity>> = state

    override suspend fun allProfiles() = values.values.toList()

    override suspend fun getProfile(id: String) = values[id]

    override suspend fun upsertProfile(profile: AwgProfileEntity) {
        if (failNextUpsert) {
            failNextUpsert = false
            error("simulated room failure")
        }
        values[profile.id] = profile
        state.value = values.values.toList()
    }

    override suspend fun deleteProfile(profile: AwgProfileEntity) {
        values.remove(profile.id)
        state.value = values.values.toList()
    }

    override suspend fun deleteAll() {
        values.clear()
        state.value = emptyList()
    }
}

private class InMemoryAwgCredentialStore : AwgCredentialStore {
    private val values = mutableMapOf<String, AwgSecrets>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) {
        values[profileId] = secrets
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryXrayMetadataStore : XrayProfileMetadataStore {
    private val values = mutableMapOf<String, XrayProfileMetadataRecord>()
    var failNextSave = false

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun list() = values.values.toList()

    override suspend fun save(record: XrayProfileMetadataRecord) {
        if (failNextSave) {
            failNextSave = false
            error("simulated metadata failure")
        }
        values[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryXraySecretStore : XrayProfileSecretStore {
    private val values = mutableMapOf<String, XrayProfileSecretRecord>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun save(record: XrayProfileSecretRecord) {
        values[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() = values.clear()
}

private class InMemoryXraySelectionStore : XrayProviderSelectionStore {
    private var value = XrayProviderSelectionRecord()
    var failNextUpdate = false

    override fun current() = value

    override fun update(record: XrayProviderSelectionRecord) {
        if (failNextUpdate) {
            failNextUpdate = false
            error("simulated xray selection failure")
        }
        value = record
    }
}
