package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileRecordPair
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasuredProfileSelectionTransactionTest : RoomProfileTest() {
    @Test
    fun `native payload with a same ID in the wrong typed namespace is superseded without mutation`() =
        runTest {
            val fixture = fixture()
            fixture.xrayLease("relay")
            val native = fixture.nativeLease()
            val wrong = fixture.lease(ProfileUtilityReference.Xray("relay"), native.payload)
            assertMeasuredSupersededWithoutMutation(fixture, wrong)
        }

    @Test
    fun `same native ID with a changed endpoint supersedes the immutable lease`() =
        runTest {
            val fixture = fixture()
            val lease = fixture.nativeLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Native
            fixture.relayProfiles.save(payload.profile.copy(server = "changed.example"))
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `same native ID with changed credentials supersedes the immutable lease`() =
        runTest {
            val fixture = fixture()
            val lease = fixture.nativeLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Native
            fixture.relayCredentials.save(payload.credentials.copy(vlessUuid = "replacement-fixture"))
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `changed Xray metadata half supersedes the captured record pair`() =
        runTest {
            val fixture = fixture()
            val lease = fixture.xrayLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Xray
            fixture.xrayMetadata.save(payload.records.metadata.copy(serverAddress = "changed.example"))
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `changed Xray secret half supersedes the captured record pair`() =
        runTest {
            val fixture = fixture()
            val lease = fixture.xrayLease()
            val payload = lease.payload as ProfileUtilitySelectionPayload.Xray
            fixture.xraySecrets.save(payload.records.secret.copy(uuid = "replacement-fixture"))
            assertMeasuredSupersededWithoutMutation(fixture, lease)
        }

    @Test
    fun `selector exact member changes supersede the captured payload`() =
        runTest {
            val fixture = fixture()
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
            val fixture = fixture()
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
            val fixture = fixture()
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
    fun `paused native selection commits exact payload and original stopped reservation before ACK`() =
        runTest {
            val fixture = fixture()
            fixture.selectorLease()
            fixture.selectorChoice.commitMember("group", "member", SelectorChoiceOrigin.Reconstruction)
            val lease = fixture.nativeLease()
            val paused = checkNotNull(fixture.pauseAuthority.snapshot())
            var reserved: DurableCommandRecord? = null
            fixture.room.beforeSeal = { _, _ ->
                reserved = fixture.pauseAuthority.snapshotAuthority().command
                assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
                assertNull(fixture.pauseAuthority.snapshot())
            }
            val selected =
                fixture.coordinator().selectMeasuredProfile(
                    lease,
                    Mode.Proxy,
                ) as ProfileUtilitySelectionResult.Selected
            fixture.clearFaults()
            val command = checkNotNull(reserved)
            assertEquals(command.commandId, selected.receipt.commandId)
            assertEquals(PauseAuthorityRef(command.generation), selected.receipt.authority)
            assertEquals(RuntimeCommandOrigin.MeasuredActivation(command.commandId, lease.reference), command.origin)
            assertEquals(RuntimeActivationPhase.Unbound, command.phase)
            assertTrue(command.generation > paused.generation)
            assertEquals("relay", fixture.settings.snapshot().relayProfileId)
            assertTrue(fixture.settings.snapshot().relayEnabled)
            assertEquals(Mode.Proxy.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            assertEquals(XrayProviderSelectionRecord(), fixture.xraySelection.current())
            assertNull(fixture.profileSelection.snapshot().selectorGroupId)
            assertNoMeasuredHistory(fixture)
            val activation = checkNotNull(fixture.pauseAuthority.bindProfileActivation(selected.receipt, Mode.Proxy))
            assertEquals(selected.receipt.commandId, activation.commandId)
            assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
            assertNoMeasuredHistory(fixture)
        }

    @Test
    fun `selector selection commits exact member and same transient receipt`() =
        runTest {
            val fixture = fixture()
            val lease = fixture.selectorLease()
            val selected =
                fixture.coordinator().selectMeasuredProfile(
                    lease,
                    Mode.VPN,
                ) as ProfileUtilitySelectionResult.Selected
            assertSame(selected.receipt, fixture.selectorSelections.manualReceipt("group"))
            assertEquals("group", fixture.profileSelection.snapshot().selectorGroupId)
            assertEquals("member", fixture.selectorSelections.snapshot("group").profileId)
            assertEquals(Mode.VPN.preferenceValue, fixture.settings.snapshot().ripdpiMode)
            assertNoMeasuredHistory(fixture)
            val committed = fixture.snapshot()
            fixture.reopen()
            assertEquals(committed, fixture.snapshot())
            assertEquals("member", fixture.selectorSelections.snapshot("group").profileId)
            assertNull(fixture.selectorSelections.manualReceipt("group"))
            val envelope =
                RuntimeActivationEnvelope(
                    selected.receipt.authority.generation,
                    selected.receipt.commandId,
                    selected.receipt.origin,
                    Mode.VPN.preferenceValue,
                )
            assertNull(fixture.pauseAuthority.activationReceiptFromEnvelope(envelope))
            assertNoMeasuredHistory(fixture)
        }

    @Test
    fun `new Pause or Stop at the reservation check preserves the exact newer intent`() =
        runTest {
            for (pause in listOf(true, false)) {
                val fixture = fixture()
                val lease = fixture.nativeLease()
                val before = fixture.snapshot()
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
                assertEquals(checkNotNull(newer), fixture.persistence.read())
                assertEquals(before, fixture.snapshot())
                assertEquals(0L, fixture.mutationGeneration.generation.value)
                assertNoMeasuredHistory(fixture)
            }
        }

    @Test
    fun `failed measured write cancels only its reservation and preserves newer Pause or Stop`() =
        runTest {
            for (newerPause in listOf(null, true, false)) {
                val fixture = fixture()
                val lease = fixture.nativeLease()
                val before = fixture.snapshot()
                val failure = IllegalStateException("measured write failed")
                var reserved: PauseAuthorityState? = null
                var newer: PauseAuthorityState? = null
                fixture.room.beforeSeal = { _, _ ->
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
                val actual =
                    checkNotNull(
                        runCatching {
                            fixture.coordinator().selectMeasuredProfile(
                                lease,
                                Mode.Proxy,
                            )
                        }.exceptionOrNull(),
                    )
                assertEquals(failure.javaClass, actual.javaClass)
                assertEquals(failure.message, actual.message)
                // Coroutine stacktrace recovery can copy an exception and retain the original as its cause.
                assertSame(failure, actual.cause ?: actual)
                fixture.clearFaults()
                if (newerPause == null) {
                    val state = checkNotNull(reserved)
                    assertEquals(
                        state.copy(
                            command = checkNotNull(state.command).copy(phase = RuntimeActivationPhase.Terminated),
                        ),
                        fixture.persistence.read(),
                    )
                } else {
                    assertEquals(checkNotNull(newer), fixture.persistence.read())
                }
                assertEquals(before, fixture.snapshot())
                fixture.reopen()
                assertEquals(before, fixture.snapshot())
                assertNoMeasuredHistory(fixture)
            }
        }
}

private suspend fun assertMeasuredSupersededWithoutMutation(
    fixture: ProfileMutationTestFixture,
    lease: ProfileUtilitySelectionLease,
) {
    val before = fixture.snapshot()
    val authority = fixture.pauseAuthority.snapshotAuthority()
    val generation = fixture.mutationGeneration.generation.value
    assertEquals(
        ProfileUtilitySelectionResult.Superseded,
        fixture.coordinator().selectMeasuredProfile(lease, Mode.Proxy),
    )
    assertEquals(before, fixture.snapshot())
    assertEquals(authority, fixture.pauseAuthority.snapshotAuthority())
    assertEquals(generation, fixture.mutationGeneration.generation.value)
}

private fun assertNoMeasuredHistory(fixture: ProfileMutationTestFixture) {
    val utility = checkNotNull(fixture.persistence.read()).profileUtility
    assertTrue(utility.recents.isEmpty())
    assertTrue(utility.acknowledged.isEmpty())
    assertEquals(0L, utility.lastSequence)
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

private suspend fun ProfileMutationTestFixture.writeGroups(groups: List<ProxyGroup>) =
    groupCatalog.replaceAfterImage(groups)
