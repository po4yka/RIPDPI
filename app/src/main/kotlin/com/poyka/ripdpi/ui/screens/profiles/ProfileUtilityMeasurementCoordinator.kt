package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import com.poyka.ripdpi.services.CandidateRelayMeasurements
import com.poyka.ripdpi.services.CandidateRelayNetworkEpoch
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import javax.inject.Inject
import javax.inject.Singleton

internal sealed interface ProfileUtilityCheckResult {
    data class Measured(
        val latencyMillis: Long,
        val observedAtMillis: Long,
        val lease: com.poyka.ripdpi.services.MeasuredProfileSelectionLease,
    ) : ProfileUtilityCheckResult

    data class Failed(
        val reason: ProfileUtilityFailure,
    ) : ProfileUtilityCheckResult
}

/** Transient only: this lease contains sensitive profile/network context and is never persisted/exported/logged. */
internal class ProfileMeasurementLease internal constructor(
    override val reference: ProfileUtilityReference,
    override val catalogGeneration: Long,
    override val expectedAuthority: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    override val payload: com.poyka.ripdpi.data.ProfileUtilitySelectionPayload,
    private val refresh: suspend () -> Boolean,
    private val payloadCheck: suspend () -> Boolean,
    private val scopeCheck: () -> Boolean,
    private val externalRefresh: suspend () -> Boolean,
    private val externalCheck: () -> Boolean,
    private val recovery: ProfileMutationRecoveryAccess,
) : com.poyka.ripdpi.data.ProfileUtilitySelectionLease {
    override suspend fun refreshEnvironment() = refresh()

    override suspend fun payloadMatches() = payloadCheck()

    override fun environmentMatchesNow() = scopeCheck()

    override suspend fun refreshExternalEnvironment() = externalRefresh()

    override fun externalEnvironmentMatchesNow() = externalCheck()

    suspend fun matches() =
        refreshEnvironment() && recovery.readRecovered { payloadMatches() && environmentMatchesNow() }

    override fun toString() = "ProfileMeasurementLease([REDACTED])"
}

private class VerifiedMeasurementLease(
    scope: ProfileMeasurementLease,
    override val configurationProof: com.poyka.ripdpi.services.CandidateConfigurationProof,
) : com.poyka.ripdpi.services.MeasuredProfileSelectionLease,
    com.poyka.ripdpi.data.ProfileUtilitySelectionLease by scope

@Singleton
internal class ProfileUtilityMeasurementCoordinator
    @Inject
    constructor(
        private val recovery: ProfileMutationRecoveryAccess,
        private val authority: PauseIntentAuthority,
        private val profiles: RelayProfileStore,
        private val credentials: RelayCredentialStore,
        private val groups: ProxyGroupRepository,
        private val probe: CandidateRelayMeasurements,
        private val xrayProbe: com.poyka.ripdpi.services.CandidateXrayMeasurements,
        private val xrayProfiles: com.poyka.ripdpi.data.xray.DurableXrayProfileStore,
        private val xrayMetadata: com.poyka.ripdpi.data.xray.XrayProfileMetadataStore,
        private val xraySecrets: com.poyka.ripdpi.data.xray.XrayProfileSecretStore,
        private val network: NetworkFingerprintProvider,
        private val networkEpoch: CandidateRelayNetworkEpoch,
        private val runtimes: ServiceRuntimeRegistry,
        private val applied: AppliedRuntimeConfigurationSource,
    ) {
        /** One physical/runtime fence spans every member of a fastest-profile operation. */
        fun captureOperationScope(): suspend () -> Boolean {
            val physical = networkEpoch.capture()
            val runtimeIds = runtimes.runtimes.value.mapValues { it.value.runtimeId }
            val configurations = appliedIdentities()
            val policies = policyIdentities()
            var environment: com.poyka.ripdpi.services.CandidateRelayProbeEnvironment? = null
            return {
                val observed = probe.captureEnvironment()
                if (environment == null) environment = observed
                physical != null && physical == networkEpoch.capture() && environment == observed &&
                    runtimeIds == runtimes.runtimes.value.mapValues { it.value.runtimeId } &&
                    configurations == appliedIdentities() && policies == policyIdentities()
            }
        }

        suspend fun check(
            reference: ProfileUtilityReference,
            url: String,
        ): ProfileUtilityCheckResult {
            val leaseFactory = captureLeaseFactory(reference)
            return if (leaseFactory == null) {
                ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.EnvironmentChanged)
            } else {
                val captured = capturePayload(reference)
                if (captured == null) {
                    ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.Unsupported)
                } else {
                    val lease = leaseFactory(captured)
                    if (!lease.matches()) {
                        ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.EnvironmentChanged)
                    } else {
                        measureCaptured(captured, url, lease)
                    }
                }
            }
        }

        private suspend fun captureLeaseFactory(
            reference: ProfileUtilityReference,
        ): (
            (Payload) -> ProfileMeasurementLease
        )? {
            val originalAuthority = authority.snapshotAuthority()
            val utility = checkNotNull(authority.states.value).profileUtility
            if (!utility.catalogReady || reference !in utility.catalog) return null
            return networkEpoch.capture()?.let { epoch ->
                val runtimeIds = runtimes.runtimes.value.mapValues { it.value.runtimeId }
                val configurationIds = appliedIdentities()
                val policies = policyIdentities()
                network.capture()?.let { fingerprint ->
                    val environment = probe.captureEnvironment()
                    var externalObservationMatches = false
                    val refreshExternal: suspend () -> Boolean = {
                        val currentFingerprint = network.capture()
                        val currentEnvironment = probe.captureEnvironment()
                        externalObservationMatches = currentFingerprint?.scopeKey() == fingerprint.scopeKey() &&
                            currentEnvironment.copy(vpnProtectionRequired = false) ==
                            environment.copy(vpnProtectionRequired = false)
                        externalObservationMatches
                    }
                    val externalCurrent = { externalObservationMatches && epoch == networkEpoch.capture() }
                    val build: (Payload) -> ProfileMeasurementLease = { captured ->
                        ProfileMeasurementLease(
                            reference,
                            utility.catalogGeneration,
                            originalAuthority,
                            captured.selection,
                            refresh = { refreshExternal() && environment == probe.captureEnvironment() },
                            payloadCheck = captured.matches,
                            scopeCheck = {
                                val currentUtility = authority.states.value?.profileUtility
                                externalCurrent() && originalAuthority == authority.snapshotAuthority() &&
                                    currentUtility?.catalogReady == true &&
                                    currentUtility.catalogGeneration == utility.catalogGeneration &&
                                    reference in currentUtility.catalog &&
                                    runtimeIds == runtimes.runtimes.value.mapValues { it.value.runtimeId } &&
                                    configurationIds == appliedIdentities() && policies == policyIdentities()
                            },
                            externalRefresh = refreshExternal,
                            externalCheck = externalCurrent,
                            recovery = recovery,
                        )
                    }
                    build
                }
            }
        }

        private suspend fun capturePayload(reference: ProfileUtilityReference): Payload? =
            recovery.readRecovered {
                when (reference) {
                    is ProfileUtilityReference.NativeRelay -> {
                        val profile = profiles.load(reference.profileId)
                        val secret = credentials.load(reference.profileId)
                        if (profile == null || secret == null) {
                            null
                        } else {
                            Payload.Native(
                                profile,
                                secret,
                                com.poyka.ripdpi.data.ProfileUtilitySelectionPayload
                                    .Native(profile, secret),
                            ) {
                                profiles.load(reference.profileId) == profile &&
                                    credentials.load(reference.profileId) == secret
                            }
                        }
                    }

                    is ProfileUtilityReference.SelectorMember -> {
                        val group = groups.list().firstOrNull { it.id == reference.groupId }
                        val member = group?.members?.firstOrNull { it.id == reference.memberId }
                        val mapped = member?.let { mapRelayProfile(it, reference.memberId) }
                        if (mapped == null) {
                            null
                        } else {
                            Payload.Native(
                                mapped.profile,
                                mapped.credentials,
                                com.poyka.ripdpi.data.ProfileUtilitySelectionPayload.Selector(
                                    reference.groupId,
                                    reference.memberId,
                                    checkNotNull(member),
                                ),
                            ) {
                                groups
                                    .list()
                                    .firstOrNull { it.id == reference.groupId }
                                    ?.members
                                    ?.singleOrNull { it.id == reference.memberId } == member
                            }
                        }
                    }

                    is ProfileUtilityReference.Xray -> {
                        xrayProfiles.load(reference.profileId)?.let { profile ->
                            val records =
                                com.poyka.ripdpi.data.xray.XrayProfileRecordPair(
                                    checkNotNull(xrayMetadata.load(reference.profileId)),
                                    checkNotNull(xraySecrets.load(reference.profileId)),
                                )
                            Payload.Xray(
                                profile,
                                com.poyka.ripdpi.data.ProfileUtilitySelectionPayload.Xray(
                                    reference.profileId,
                                    records,
                                ),
                            ) {
                                xrayProfiles.load(reference.profileId) == profile &&
                                    xrayMetadata.load(reference.profileId) == records.metadata &&
                                    xraySecrets.load(reference.profileId) == records.secret
                            }
                        }
                    }
                }
            }

        private suspend fun measureCaptured(
            captured: Payload,
            url: String,
            lease: ProfileMeasurementLease,
        ): ProfileUtilityCheckResult {
            val measured =
                when (captured) {
                    is Payload.Native -> probe.measure(captured.profile, captured.secret, url)
                    is Payload.Xray -> xrayProbe.measure(captured.profile, url)
                }
            return when (measured) {
                is CandidateRelayMeasurement.Succeeded -> {
                    if (lease.matches()) {
                        ProfileUtilityCheckResult.Measured(
                            measured.latencyMillis,
                            System.currentTimeMillis(),
                            VerifiedMeasurementLease(lease, measured.configurationProof),
                        )
                    } else {
                        ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.EnvironmentChanged)
                    }
                }

                CandidateRelayMeasurement.Busy -> {
                    ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.Busy)
                }

                CandidateRelayMeasurement.Unsupported -> {
                    ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.Unsupported)
                }

                CandidateRelayMeasurement.EnvironmentChanged -> {
                    ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.EnvironmentChanged)
                }

                CandidateRelayMeasurement.CleanupPending -> {
                    ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.CleanupPending)
                }

                is CandidateRelayMeasurement.TimedOut -> {
                    ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.TimedOut)
                }

                is CandidateRelayMeasurement.Failed -> {
                    ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.HttpFailed)
                }
            }
        }

        private fun policyIdentities() =
            runtimes.runtimes.value.mapValues { (_, runtime) ->
                runtime.activeConnectionPolicy.value?.let {
                    Triple(
                        it.policySignature,
                        it.appliedAt,
                        it.fingerprintHash,
                    )
                }
            }

        private fun appliedIdentities() =
            applied.applications.value.mapValues { (_, value) ->
                (value as? RuntimeConfigurationApplication.Applied)?.configuration?.let {
                    Triple(it.runtimeId, it.revision, it.mode)
                }
            }

        private sealed class Payload(
            val selection: com.poyka.ripdpi.data.ProfileUtilitySelectionPayload,
            val matches: suspend () -> Boolean,
        ) {
            class Native(
                val profile: com.poyka.ripdpi.data.RelayProfileRecord,
                val secret: com.poyka.ripdpi.data.RelayCredentialRecord,
                selection: com.poyka.ripdpi.data.ProfileUtilitySelectionPayload,
                matches: suspend () -> Boolean,
            ) : Payload(selection, matches)

            class Xray(
                val profile: com.poyka.ripdpi.data.xray.XrayProfile,
                selection: com.poyka.ripdpi.data.ProfileUtilitySelectionPayload,
                matches: suspend () -> Boolean,
            ) : Payload(selection, matches)

            final override fun toString() = "ProfileMeasurementPayload([REDACTED])"
        }
    }
