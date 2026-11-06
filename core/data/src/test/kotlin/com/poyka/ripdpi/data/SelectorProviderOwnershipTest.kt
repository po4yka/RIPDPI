package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorProviderOwnershipTest : RoomProfileTest() {
    @Test
    fun `manual selector activation clears standalone providers in the same commit`() =
        runTest {
            val fixture = initialized()
            fixture.xraySelection.update(previousXray)
            fixture.warpProfiles.setActiveProfileId("previous-warp")
            fixture.settings.update { setWarpEnabled(true) }
            fixture.selectorSelections.select("group", "member")
            assertEquals(VpnProviderKind.Native, fixture.xraySelection.current().kind)
            assertNull(fixture.warpProfiles.activeProfileId())
            assertFalse(fixture.settings.snapshot().warpEnabled)
            assertEquals("group", fixture.selectorChoice.activeGroupId.first())
            assertEquals("member", fixture.selectorSelections.snapshot("group").profileId)
            assertNotNull(fixture.selectorSelections.manualReceipt("group"))
        }

    @Test
    fun `failed selector transaction preserves providers and never replays after a newer Stop`() =
        runTest {
            val fixture = initialized()
            fixture.xraySelection.update(previousXray)
            fixture.warpProfiles.setActiveProfileId("previous-warp")
            fixture.settings.update { setWarpEnabled(true) }
            val before = fixture.snapshot()
            fixture.failWrite("app_settings")
            assertTrue(runCatching { fixture.selectorSelections.select("group", "member") }.isFailure)
            assertEquals(before, fixture.snapshot())
            assertNull(fixture.selectorSelections.manualReceipt("group"))
            fixture.clearFaults()
            val stop = fixture.pauseAuthority.reserveStop()
            val newer = XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "newer")
            fixture.xraySelection.update(newer)
            fixture.coordinator().recover()
            assertEquals(stop.authority, fixture.pauseAuthority.reference())
            assertEquals(newer, fixture.xraySelection.current())
            assertNull(fixture.selectorChoice.activeGroupId.first())
            fixture.reopen()
            assertEquals(newer, fixture.xraySelection.current())
            assertEquals("previous-warp", fixture.warpProfiles.activeProfileId())
            assertNull(fixture.selectorSelections.snapshot("group").profileId)
        }

    @Test
    fun `prepared selector cannot replace providers after a newer Stop`() =
        runTest {
            val fixture = initialized()
            fixture.xraySelection.update(previousXray)
            val prepared = fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitActivation)
            val stop = fixture.pauseAuthority.reserveStop()
            val before = fixture.snapshot()
            val outcome = fixture.coordinator().activateSelector(prepared, "group", "member", fixture.selectorChoice)
            assertEquals(ProfileMutationOutcome.Superseded, outcome)
            assertEquals(before, fixture.snapshot())
            assertEquals(stop.authority, fixture.pauseAuthority.reference())
            assertNull(fixture.selectorSelections.manualReceipt("group"))
        }

    @Test
    fun `Stop queued during a transaction makes its activation receipt unusable`() =
        runTest {
            val fixture = initialized()
            val barrier = ProfileWriteBarrier("app_settings")
            fixture.room.beforeSeal = barrier::beforeSeal
            val prepared = fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitActivation)
            val selection =
                async { fixture.coordinator().activateSelector(prepared, "group", "member", fixture.selectorChoice) }
            try {
                barrier.awaitEntry()
                val stop = async(Dispatchers.IO) { fixture.pauseAuthority.reserveStop() }
                barrier.release()
                val outcome = selection.await()
                val stopped = stop.await()
                assertEquals(stopped.authority, fixture.pauseAuthority.reference())
                assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
                (outcome as? ProfileMutationOutcome.Reserved)?.receipt?.let {
                    assertFalse(fixture.pauseAuthority.isCurrent(it))
                    assertNull(fixture.pauseAuthority.bindProfileActivation(it as ProfileActivationReceipt, Mode.VPN))
                }
            } finally {
                barrier.release()
            }
        }

    @Test
    fun `reopening keeps committed selection without restoring a manual activation capability`() =
        runTest {
            val fixture = initialized()
            fixture.selectorSelections.select("group", "member")
            val receipt = checkNotNull(fixture.selectorSelections.manualReceipt("group"))
            val committed = fixture.snapshot()
            fixture.reopen()
            assertEquals(committed, fixture.snapshot())
            assertEquals("group", fixture.selectorChoice.activeGroupId.first())
            assertEquals("member", fixture.selectorSelections.snapshot("group").profileId)
            assertNull(fixture.selectorSelections.manualReceipt("group"))
            assertTrue(
                fixture.pauseAuthority.states.value!!
                    .profileUtility.recents
                    .isEmpty(),
            )
            assertEquals(
                receipt.commandId,
                fixture.pauseAuthority
                    .snapshotAuthority()
                    .command
                    ?.commandId,
            )
        }

    @Test
    fun `standalone AWG compensation restores only its captured provider state`() =
        runTest {
            val fixture = initialized()
            fixture.selectorChoice.commitMember("group", "member", SelectorChoiceOrigin.Reconstruction)
            fixture.warpProfiles.setActiveProfileId("previous-warp")
            fixture.settings.update { setWarpEnabled(true) }
            fixture.xraySelection.update(previousXray)
            val outcome =
                fixture.coordinator().activateStandaloneAwg(
                    fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitActivation),
                    "awg",
                )
            val receipt = (outcome as ProfileMutationOutcome.Reserved).receipt as ProfileActivationReceipt
            assertNull(fixture.selectorChoice.activeGroupId.first())
            assertNull(fixture.warpProfiles.activeProfileId())
            assertFalse(fixture.settings.snapshot().warpEnabled)
            assertEquals("awg", fixture.profileSelection.snapshot().awgProfileId)
            assertTrue(fixture.coordinator().compensateStandaloneAwg(receipt, "awg"))
            assertEquals("group", fixture.selectorChoice.activeGroupId.first())
            assertEquals("previous-warp", fixture.warpProfiles.activeProfileId())
            assertTrue(fixture.settings.snapshot().warpEnabled)
            assertEquals(previousXray, fixture.xraySelection.current())
            assertFalse(fixture.coordinator().compensateStandaloneAwg(receipt, "awg"))
        }

    @Test
    fun `new Stop prevents old AWG compensation from restoring selector or Xray`() =
        runTest {
            val fixture = initialized()
            fixture.selectorChoice.commitMember("group", "member", SelectorChoiceOrigin.Reconstruction)
            fixture.xraySelection.update(previousXray)
            val outcome =
                fixture.coordinator().activateStandaloneAwg(
                    fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitActivation),
                    "awg",
                )
            val receipt = (outcome as ProfileMutationOutcome.Reserved).receipt as ProfileActivationReceipt
            val stop = fixture.pauseAuthority.reserveStop()
            assertFalse(fixture.coordinator().compensateStandaloneAwg(receipt, "awg"))
            assertNull(fixture.selectorChoice.activeGroupId.first())
            assertEquals(VpnProviderKind.Native, fixture.xraySelection.current().kind)
            assertTrue(fixture.coordinator().clearStandaloneAwg(stop, "awg"))
            assertFalse(fixture.coordinator().clearStandaloneAwg(stop, "awg"))
            assertEquals(stop.authority, fixture.pauseAuthority.reference())
        }

    @Test
    fun `explicit WARP clears selector while saved WARP edits retain it`() =
        runTest {
            val fixture = initialized()
            fixture.selectorChoice.commitMember("group", "member", SelectorChoiceOrigin.Reconstruction)
            val profile = WarpProfile(id = "warp", setupState = WarpSetupStateProvisioned)
            val credentials = WarpCredentials(profileId = profile.id, deviceId = "device", accessToken = "fixture")
            fixture.coordinator().upsertWarp(
                fixture.coordinator().captureMutation(ProfileMutationOrigin.SavedEdit),
                profile,
                credentials,
                emptyList(),
                false,
                WarpScannerModeAutomatic,
            )
            assertEquals("group", fixture.selectorChoice.activeGroupId.first())
            val outcome =
                fixture.coordinator().upsertWarp(
                    fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitActivation),
                    profile,
                    credentials,
                    emptyList(),
                    true,
                    WarpScannerModeAutomatic,
                )
            assertTrue(outcome is ProfileMutationOutcome.Reserved)
            assertNull(fixture.selectorChoice.activeGroupId.first())
            assertEquals("warp", fixture.warpProfiles.activeProfileId())
            assertEquals("warp", fixture.settings.snapshot().warpProfileId)
            assertEquals(VpnProviderKind.Native, fixture.xraySelection.current().kind)
        }

    private suspend fun initialized() = fixture().also { it.initialize() }

    private val previousXray = XrayProviderSelectionRecord.of(VpnProviderKind.Xray, "previous-xray")
}
