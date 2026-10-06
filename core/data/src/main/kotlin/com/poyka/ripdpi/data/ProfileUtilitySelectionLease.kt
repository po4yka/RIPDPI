package com.poyka.ripdpi.data

/** Ephemeral measurement ownership, consumed while holding the profile recovery transaction. */
interface ProfileUtilitySelectionLease {
    val reference: ProfileUtilityReference
    val catalogGeneration: Long
    val expectedAuthority: RuntimeAuthoritySnapshot
    val payload: ProfileUtilitySelectionPayload

    /** Capture current network/policy observations before entering the short authority operation. */
    suspend fun refreshEnvironment(): Boolean

    /** Called inside the existing profile mutex, never recursively entering recovery. */
    suspend fun payloadMatches(): Boolean

    /** In-memory comparison only: no data access, native calls, dispatch or suspension. */
    fun environmentMatchesNow(): Boolean

    /** Owned command/catalog/runtime changes are excluded; external network/payload proof is retained. */
    suspend fun refreshExternalEnvironment(): Boolean

    fun externalEnvironmentMatchesNow(): Boolean
}

sealed class ProfileUtilitySelectionPayload {
    class Native(
        val profile: RelayProfileRecord,
        val credentials: RelayCredentialRecord,
    ) : ProfileUtilitySelectionPayload()

    class Xray(
        val profileId: String,
        val records: com.poyka.ripdpi.data.xray.XrayProfileRecordPair,
    ) : ProfileUtilitySelectionPayload()

    class Selector(
        val groupId: String,
        val memberId: String,
        val member: ProxyProfile,
    ) : ProfileUtilitySelectionPayload()

    final override fun toString() = "ProfileUtilitySelectionPayload([REDACTED])"
}
