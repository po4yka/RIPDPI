package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveringConnectionPolicyResolverTest {
    @Test
    fun `recovery completes before policy resolution and arguments are forwarded`() =
        runTest {
            val events = mutableListOf<String>()
            val fingerprint = sampleFingerprint()
            val expected = sampleResolution(mode = Mode.Proxy)
            val delegate =
                object : ConnectionPolicyResolver {
                    override suspend fun resolve(
                        mode: Mode,
                        resolverOverride: TemporaryResolverOverride?,
                        fingerprint: NetworkFingerprint?,
                        handoverClassification: String?,
                    ): ConnectionPolicyResolution {
                        events += "resolve:$mode:${fingerprint?.transport}:$handoverClassification"
                        return expected
                    }
                }
            val resolver =
                RecoveringConnectionPolicyResolver(
                    delegate = delegate,
                    profileMutations = RecoveryOnlyProfileMutationCoordinator { events += "recover" },
                )

            val actual =
                resolver.resolve(
                    mode = Mode.Proxy,
                    fingerprint = fingerprint,
                    handoverClassification = "transport_switch",
                )

            assertEquals(expected, actual)
            assertEquals(listOf("recover", "resolve:Proxy:${fingerprint.transport}:transport_switch"), events)
        }

    @Test
    fun `recovery failure prevents stale policy resolution`() =
        runTest {
            var delegateCalls = 0
            val resolver =
                RecoveringConnectionPolicyResolver(
                    delegate = countingDelegate { delegateCalls += 1 },
                    profileMutations = RecoveryOnlyProfileMutationCoordinator { error("recovery failed") },
                )

            val failure = runCatching { resolver.resolve(Mode.VPN) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(0, delegateCalls)
        }

    @Test
    fun `recovery cancellation prevents policy resolution and propagates`() =
        runTest {
            var delegateCalls = 0
            val resolver =
                RecoveringConnectionPolicyResolver(
                    delegate = countingDelegate { delegateCalls += 1 },
                    profileMutations = RecoveryOnlyProfileMutationCoordinator { throw CancellationException("stop") },
                )

            val failure = runCatching { resolver.resolve(Mode.Proxy) }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(0, delegateCalls)
        }

    private fun countingDelegate(onResolve: () -> Unit): ConnectionPolicyResolver =
        object : ConnectionPolicyResolver {
            override suspend fun resolve(
                mode: Mode,
                resolverOverride: TemporaryResolverOverride?,
                fingerprint: NetworkFingerprint?,
                handoverClassification: String?,
            ): ConnectionPolicyResolution {
                onResolve()
                return sampleResolution(mode = mode)
            }
        }
}

internal class RecoveryOnlyProfileMutationCoordinator(
    private val recovery: suspend () -> Unit,
) : ProfileMutationCoordinator {
    override suspend fun <T> mutateCatalog(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        block: suspend () -> T,
    ): T {
        check(commitMutationIntent(preparation) != com.poyka.ripdpi.data.ProfileMutationOutcome.Superseded)
        return block()
    }

    override suspend fun <T> mutateReservedCatalog(
        receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
        block: suspend () -> T,
    ): T = block()

    override suspend fun activateSelector(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        groupId: String,
        memberId: String,
        choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome {
        val outcome = commitMutationIntent(preparation)
        val receipt =
            (outcome as? com.poyka.ripdpi.data.ProfileMutationOutcome.Reserved)?.receipt
                as? com.poyka.ripdpi.data.ProfileActivationReceipt
        if (receipt != null) {
            choice.commitMember(
                groupId,
                memberId,
                com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                    .Manual(receipt),
            )
        }
        return outcome
    }

    override suspend fun commitMutationIntent(preparation: com.poyka.ripdpi.data.ProfileMutationPreparation) =
        com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)

    override suspend fun captureMutation(origin: com.poyka.ripdpi.data.ProfileMutationOrigin) =
        com.poyka.ripdpi.data
            .ProfileMutationPreparation(
                origin,
                com.poyka.ripdpi.data
                    .testPauseAuthority()
                    .reference(),
            )

    override fun warpRuntimeRevision(profileId: String): Long = 0L

    override suspend fun recover() = recovery()

    override suspend fun <T> readRecovered(block: suspend () -> T): T {
        recover()
        return block()
    }

    override suspend fun runReset(block: suspend (com.poyka.ripdpi.data.DurableCommandReceipt) -> Unit) = unsupported()

    override suspend fun activateStandaloneAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome = error("Standalone activation is outside this test boundary")

    override suspend fun compensateStandaloneAwg(
        receipt: com.poyka.ripdpi.data.ProfileActivationReceipt,
        expectedProfileId: String,
    ): Boolean = error("Standalone compensation is outside this test boundary")

    override suspend fun clearStandaloneAwg(
        receipt: com.poyka.ripdpi.data.RuntimeStopReceipt,
        expectedProfileId: String,
    ): Boolean = error("Standalone deactivation is outside this test boundary")

    override suspend fun upsertAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ) = unsupported()

    override suspend fun deleteAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ) = unsupported()

    override suspend fun upsertRelay(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: XrayProviderSelectionRecord?,
        expectedState: com.poyka.ripdpi.data.ExpectedRelayProfileState?,
    ) = unsupported()

    override suspend fun upsertWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ) = unsupported()

    override suspend fun upsertWarpForRuntimeProvisioning(
        profile: com.poyka.ripdpi.data.WarpProfile,
        credentials: com.poyka.ripdpi.data.WarpCredentials,
        endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
        expectedCredentials: com.poyka.ripdpi.data.WarpCredentials,
        expectedRevision: Long,
    ): Boolean = unsupported()

    override suspend fun deleteWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
        clearActive: Boolean,
    ) = unsupported()

    override suspend fun deactivateWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ) = unsupported()

    override suspend fun replacePrivateBackup(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        data: BackupPrivateDataV1,
        rollbackData: BackupPrivateDataV1?,
    ) = unsupported()

    private fun unsupported(): Nothing = error("unsupported test mutation")
}
