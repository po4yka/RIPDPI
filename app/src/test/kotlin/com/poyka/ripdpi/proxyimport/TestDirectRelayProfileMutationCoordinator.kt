package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.rollbackStoreMutation
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord

class TestDirectRelayProfileMutationCoordinator(
    private val profiles: RelayProfileStore,
    private val credentials: RelayCredentialStore,
    private val settings: AppSettingsRepository,
) : ProfileMutationCoordinator {
    val testAuthority =
        com.poyka.ripdpi.data
            .testPauseAuthority()

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
        testAuthority.invalidateForMutation(
            preparation.origin,
            java.util.UUID
                .randomUUID()
                .toString(),
            preparation.expectedPauseAuthority,
        )

    override suspend fun captureMutation(origin: com.poyka.ripdpi.data.ProfileMutationOrigin) =
        com.poyka.ripdpi.data
            .ProfileMutationPreparation(origin, testAuthority.reference())

    override fun warpRuntimeRevision(profileId: String): Long = 0L

    override suspend fun recover() = Unit

    override suspend fun <T> readRecovered(block: suspend () -> T): T = block()

    override suspend fun runReset(
        block: suspend (com.poyka.ripdpi.data.DurableCommandReceipt) -> Unit,
    ): com.poyka.ripdpi.data.DurableCommandReceipt {
        val receipt =
            com.poyka.ripdpi.data
                .testPauseAuthority()
                .reserveStop()
        block(receipt)
        return receipt
    }

    override suspend fun upsertRelay(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: com.poyka.ripdpi.proto.AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord?,
        expectedState: com.poyka.ripdpi.data.ExpectedRelayProfileState?,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome {
        if (expectedState != null) {
            require(
                profiles.load(profile.id) == expectedState.profile &&
                    this.credentials.load(profile.id) == expectedState.credentials,
            )
        }
        val outcome = commitMutationIntent(preparation)
        val previousProfile = profiles.load(profile.id)
        val previousCredentials = this.credentials.load(profile.id)
        val previousSettings = settings.snapshot()
        try {
            profiles.save(profile)
            this.credentials.save(credentials)
            check(xraySelectionAfterImage == null) { "Direct relay activation cannot update Xray provider selection" }
            if (settingsAfterImage != null) {
                settings.replace(settingsAfterImage)
            } else if (select || modeAfterImage != null) {
                settings.update {
                    setRelayEnabled(enabled)
                    setRelayKind(profile.kind)
                    setRelayProfileId(profile.id)
                    setRelayServer(profile.server)
                    setRelayServerPort(profile.serverPort)
                    setRelayServerName(profile.serverName)
                    setRelayRealityPublicKey(profile.realityPublicKey)
                    setRelayRealityShortId(profile.realityShortId)
                    setRelayVlessTransport(profile.vlessTransport)
                    setRelayXhttpPath(profile.xhttpPath)
                    setRelayXhttpHost(profile.xhttpHost)
                    setRelayXhttpMode(profile.xhttpMode)
                    setRelayUdpEnabled(profile.udpEnabled)
                    setRelayMieruProtocol(profile.mieruProtocol)
                    setRelayMieruMultiplexing(profile.mieruMultiplexing)
                    setRelayMieruMtu(profile.mieruMtu)
                    setRelaySshAuthType(profile.sshAuthType)
                    setRelaySshHostKeyFingerprint(profile.sshHostKeyFingerprint)
                    setRelaySshStrictHostKey(profile.sshStrictHostKey)
                    modeAfterImage?.let(::setRipdpiMode)
                }
            }
        } catch (failure: Exception) {
            failure.rollbackStoreMutation(
                { if (previousProfile == null) profiles.clear(profile.id) else profiles.save(previousProfile) },
                {
                    if (previousCredentials ==
                        null
                    ) {
                        this.credentials.clear(profile.id)
                    } else {
                        this.credentials.save(previousCredentials)
                    }
                },
                { settings.replace(previousSettings) },
            )
        }

        return outcome
    }

    override suspend fun upsertAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: com.poyka.ripdpi.data.awg.AwgProfileEntity,
        secrets: com.poyka.ripdpi.data.awg.AwgSecrets,
    ) = unsupported()

    override suspend fun deleteAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ) = unsupported()

    override suspend fun upsertWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: com.poyka.ripdpi.data.WarpProfile,
        credentials: com.poyka.ripdpi.data.WarpCredentials,
        endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
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
        data: com.poyka.ripdpi.data.backup.BackupPrivateDataV1,
        rollbackData: com.poyka.ripdpi.data.backup.BackupPrivateDataV1?,
    ) = unsupported()

    private fun unsupported(): Nothing = error("Only Relay mutations are supported")
}
