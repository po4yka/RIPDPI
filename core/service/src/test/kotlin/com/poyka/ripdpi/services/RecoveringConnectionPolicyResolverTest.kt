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

private class RecoveryOnlyProfileMutationCoordinator(
    private val recovery: suspend () -> Unit,
) : ProfileMutationCoordinator {
    override suspend fun recover() = recovery()

    override suspend fun <T> readRecovered(block: suspend () -> T): T {
        recover()
        return block()
    }

    override suspend fun runReset(block: suspend () -> Unit) = unsupported()

    override suspend fun upsertAwg(
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ) = unsupported()

    override suspend fun deleteAwg(profileId: String) = unsupported()

    override suspend fun upsertRelay(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: XrayProviderSelectionRecord?,
    ) = unsupported()

    override suspend fun upsertWarp(
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ) = unsupported()

    override suspend fun deleteWarp(
        profileId: String,
        clearActive: Boolean,
    ) = unsupported()

    override suspend fun deactivateWarp(profileId: String) = unsupported()

    override suspend fun replacePrivateBackup(
        data: BackupPrivateDataV1,
        rollbackData: BackupPrivateDataV1?,
    ) = unsupported()

    private fun unsupported(): Nothing = error("unsupported test mutation")
}
