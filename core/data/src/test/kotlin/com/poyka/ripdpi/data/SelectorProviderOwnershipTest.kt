package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SharedPreferencesSelectorSelectionStore
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProviderSelectionStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectorProviderOwnershipTest {
    @Test fun `manual selector activation clears standalone Xray before member publication`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val authority = testPauseAuthority()
            val active = SelectorActiveGroupStore(context, authority)
            val xray = SharedPreferencesXrayProviderSelectionStore(context)
            xray.update(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "previous-xray"))
            val settings = providerSettings()
            val source =
                selectorTransitionCoordinator(
                    authority,
                    active,
                    xray,
                    settings,
                    SharedPreferencesWarpProfileStore(context),
                )
            source.recover()
            val selected =
                SharedPreferencesSelectorSelectionStore(
                    context,
                    source,
                    authority,
                    active,
                )

            selected.select("group", "member")

            assertEquals(VpnProviderKind.Native, xray.current().kind)
            assertEquals("group", active.activeGroupId.value)
            assertEquals("member", selected.snapshot("group").profileId)
            assertNotNull(selected.manualReceipt("group"))
        }

    @Test
    fun `new Stop during owned Warp pointer await preserves providers and skips selector publication`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val authority = testPauseAuthority()
            val active = SelectorActiveGroupStore(context, authority)
            val xray = SharedPreferencesXrayProviderSelectionStore(context)
            val previous = XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "previous-xray")
            xray.update(previous)
            val actualWarp = SharedPreferencesWarpProfileStore(context)
            actualWarp.setActiveProfileId("previous-warp")
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val delayedWarp =
                object : WarpProfileStore by actualWarp {
                    override suspend fun setActiveProfileIdOwned(
                        profileId: String?,
                        authority: PauseIntentAuthority,
                        reference: PauseAuthorityRef,
                        commandId: String,
                    ): Boolean {
                        entered.complete(Unit)
                        release.await()
                        return actualWarp.setActiveProfileIdOwned(profileId, authority, reference, commandId)
                    }
                }
            val settings = providerSettings()
            settings.update { setWarpEnabled(true) }
            val coordinator = selectorTransitionCoordinator(authority, active, xray, settings, delayedWarp)
            coordinator.recover()
            val selected = SharedPreferencesSelectorSelectionStore(context, coordinator, authority, active)
            val operation = async { selected.select("group", "member") }
            entered.await()
            val stop = authority.reserveStop()
            release.complete(Unit)
            operation.await()
            assertEquals(stop.authority, authority.reference())
            assertEquals(previous, xray.current())
            assertEquals("previous-warp", actualWarp.activeProfileId())
            assertEquals(true, settings.snapshot().warpEnabled)
            assertEquals(null, active.activeGroupId.value)
            assertEquals(null, selected.snapshot("group").profileId)
            assertEquals(null, selected.manualReceipt("group"))
        }

    @Test
    fun `interrupted selector journal cannot replay provider pointers after newer Stop`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val authority = testPauseAuthority()
            val active = SelectorActiveGroupStore(context, authority)
            val xray = SharedPreferencesXrayProviderSelectionStore(context)
            val warp = SharedPreferencesWarpProfileStore(context)
            val settings = providerSettings()
            var failPrepare = true
            val coordinator =
                selectorTransitionCoordinator(
                    authority,
                    active,
                    xray,
                    settings,
                    warp,
                )
            coordinator.recover()
            // A checked store failure retains the real prepared journal; no fabricated marker.
            val original = XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "current-xray")
            xray.update(original)
            val source = coordinator
            val selected = SharedPreferencesSelectorSelectionStore(context, source, authority, active)
            // Fault injected via the owning settings store after the reservation is durably committed.
            val faultSettings =
                object : AppSettingsRepository by settings {
                    override suspend fun update(transform: com.poyka.ripdpi.proto.AppSettings.Builder.() -> Unit) {
                        if (failPrepare) {
                            failPrepare = false
                            error("settings commit failed")
                        }
                        settings.update(transform)
                    }
                }
            val faultCoordinator = selectorTransitionCoordinator(authority, active, xray, faultSettings, warp)
            val faultSelection = SharedPreferencesSelectorSelectionStore(context, faultCoordinator, authority, active)
            val failure = runCatching { faultSelection.select("group", "member") }.exceptionOrNull()
            assertEquals("settings commit failed", failure?.message)
            val stop = authority.reserveStop()
            val newer = XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "newer-xray")
            xray.update(newer)
            faultCoordinator.recover()
            assertEquals(stop.authority, authority.reference())
            assertEquals(newer, xray.current())
            assertEquals(null, active.activeGroupId.value)
            assertEquals(null, selected.snapshot("group").profileId)
            assertEquals(null, selected.manualReceipt("group"))
        }

    @Test
    fun `owned selector reconstruction finishes metadata with original terminated command and no manual capability`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val authority = testPauseAuthority()
            val active = SelectorActiveGroupStore(context, authority)
            val xray = SharedPreferencesXrayProviderSelectionStore(context)
            val settings = providerSettings()
            var failWrite = true
            val faultSettings =
                object : AppSettingsRepository by settings {
                    override suspend fun update(transform: com.poyka.ripdpi.proto.AppSettings.Builder.() -> Unit) {
                        if (failWrite) {
                            failWrite = false
                            error("settings commit failed")
                        }
                        settings.update(transform)
                    }
                }
            val coordinator =
                selectorTransitionCoordinator(
                    authority,
                    active,
                    xray,
                    faultSettings,
                    SharedPreferencesWarpProfileStore(context),
                )
            val selected = SharedPreferencesSelectorSelectionStore(context, coordinator, authority, active)
            assertEquals(
                "settings commit failed",
                runCatching { selected.select("group", "member") }.exceptionOrNull()?.message,
            )
            val reserved = checkNotNull(authority.snapshotAuthority().command)
            coordinator.recover()
            val recovered = checkNotNull(authority.snapshotAuthority().command)
            assertEquals(reserved.commandId, recovered.commandId)
            assertEquals(reserved.generation, recovered.generation)
            assertEquals(RuntimeActivationPhase.Terminated, recovered.phase)
            assertEquals(DesiredRuntimeState.Stopped, authority.snapshotAuthority().desired)
            assertEquals("group", active.activeGroupId.value)
            assertEquals("member", selected.snapshot("group").profileId)
            assertEquals(true, selected.snapshot("group").isManual)
            assertEquals(null, selected.manualReceipt("group"))
            assertEquals(VpnProviderKind.Native, xray.current().kind)
            assertEquals(
                0,
                authority.states.value
                    ?.profileUtility
                    ?.recents
                    ?.size,
            )
        }

    @Test
    fun `standalone AWG journals provider pointers and compensation restores only original command`() =
        runTest {
            val fixture = providerFixture()
            fixture.active.commitMember(
                "group",
                "member",
                com.poyka.ripdpi.data.selector.SelectorChoiceOrigin.Reconstruction,
            )
            fixture.warp.setActiveProfileId("previous-warp")
            fixture.settings.update { setWarpEnabled(true) }
            val previous = XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "previous-xray")
            fixture.xray.update(previous)
            val preparation = fixture.coordinator.captureMutation(ProfileMutationOrigin.ExplicitActivation)
            val outcome = fixture.coordinator.activateStandaloneAwg(preparation, "awg")
            val receipt = (outcome as ProfileMutationOutcome.Reserved).receipt as ProfileActivationReceipt
            assertEquals(null, fixture.active.activeGroupId.value)
            assertEquals(null, fixture.warp.activeProfileId())
            assertEquals(false, fixture.settings.snapshot().warpEnabled)
            assertEquals(VpnProviderKind.Native, fixture.xray.current().kind)
            assertEquals(true, fixture.coordinator.compensateStandaloneAwg(receipt, "awg"))
            assertEquals("group", fixture.active.activeGroupId.value)
            assertEquals("previous-warp", fixture.warp.activeProfileId())
            assertEquals(true, fixture.settings.snapshot().warpEnabled)
            assertEquals(previous, fixture.xray.current())
            assertEquals(false, fixture.coordinator.compensateStandaloneAwg(receipt, "awg"))
        }

    @Test
    fun `new Stop prevents old AWG compensation from restoring selector or Xray`() =
        runTest {
            val fixture = providerFixture()
            fixture.active.commitMember(
                "group",
                "member",
                com.poyka.ripdpi.data.selector.SelectorChoiceOrigin.Reconstruction,
            )
            fixture.xray.update(XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "previous-xray"))
            val outcome =
                fixture.coordinator.activateStandaloneAwg(
                    fixture.coordinator.captureMutation(ProfileMutationOrigin.ExplicitActivation),
                    "awg",
                )
            val receipt = (outcome as ProfileMutationOutcome.Reserved).receipt as ProfileActivationReceipt
            val stop = fixture.authority.reserveStop()
            assertEquals(false, fixture.coordinator.compensateStandaloneAwg(receipt, "awg"))
            assertEquals(null, fixture.active.activeGroupId.value)
            assertEquals(VpnProviderKind.Native, fixture.xray.current().kind)
            assertEquals(true, fixture.coordinator.clearStandaloneAwg(stop, "awg"))
            assertEquals(false, fixture.coordinator.clearStandaloneAwg(stop, "awg"))
            assertEquals(stop.authority, fixture.authority.reference())
        }

    @Test
    fun `explicit WARP clears selector while saved WARP edits retain it`() =
        runTest {
            val fixture = providerFixture()
            fixture.active.commitMember(
                "group",
                "member",
                com.poyka.ripdpi.data.selector.SelectorChoiceOrigin.Reconstruction,
            )
            val profile = WarpProfile(id = "warp", setupState = WarpSetupStateProvisioned)
            val credentials = WarpCredentials(profileId = profile.id, deviceId = "device", accessToken = "fixture")
            fixture.coordinator.upsertWarp(
                fixture.coordinator.captureMutation(ProfileMutationOrigin.SavedEdit),
                profile,
                credentials,
                emptyList(),
                false,
                WarpScannerModeAutomatic,
            )
            assertEquals("group", fixture.active.activeGroupId.value)
            val outcome =
                fixture.coordinator.upsertWarp(
                    fixture.coordinator.captureMutation(ProfileMutationOrigin.ExplicitActivation),
                    profile,
                    credentials,
                    emptyList(),
                    true,
                    WarpScannerModeAutomatic,
                )
            assertEquals(true, outcome is ProfileMutationOutcome.Reserved)
            assertEquals(null, fixture.active.activeGroupId.value)
            assertEquals("warp", fixture.warp.activeProfileId())
            assertEquals("warp", fixture.settings.snapshot().warpProfileId)
            assertEquals(VpnProviderKind.Native, fixture.xray.current().kind)
        }
}

private fun providerSettings(): AppSettingsRepository =
    object : AppSettingsRepository {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(AppSettingsSerializer.defaultValue)
        override val settings = state

        override suspend fun snapshot() = state.value

        override suspend fun update(transform: com.poyka.ripdpi.proto.AppSettings.Builder.() -> Unit) {
            state.value =
                state.value
                    .toBuilder()
                    .apply(transform)
                    .build()
        }

        override suspend fun replace(settings: com.poyka.ripdpi.proto.AppSettings) {
            state.value = settings
        }
    }

private class ProviderFixture(
    val authority: PauseIntentAuthority,
    val active: SelectorActiveGroupStore,
    val xray: SharedPreferencesXrayProviderSelectionStore,
    val settings: AppSettingsRepository,
    val warp: SharedPreferencesWarpProfileStore,
    val coordinator: ProfileMutationRecoveryCoordinator,
)

private suspend fun providerFixture(): ProviderFixture {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val authority = testPauseAuthority()
    val active = SelectorActiveGroupStore(context, authority)
    val xray = SharedPreferencesXrayProviderSelectionStore(context)
    val settings = providerSettings()
    val warp = SharedPreferencesWarpProfileStore(context)
    val coordinator = selectorTransitionCoordinator(authority, active, xray, settings, warp)
    coordinator.recover()
    return ProviderFixture(authority, active, xray, settings, warp, coordinator)
}
