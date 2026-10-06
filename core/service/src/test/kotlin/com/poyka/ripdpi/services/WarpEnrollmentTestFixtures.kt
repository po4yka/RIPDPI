package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore

internal class FakeWarpProvisioningClient(
    private val registerResult: WarpProvisioningResult,
    private val refreshError: Exception? = null,
) : WarpProvisioningClient {
    override suspend fun register(
        request: WarpRegisterDeviceRequest,
        bootstrapProxy: java.net.Proxy?,
    ): WarpProvisioningResult = registerResult

    override suspend fun refresh(
        credentials: WarpCredentials,
        bootstrapProxy: java.net.Proxy?,
    ): WarpProvisioningResult {
        refreshError?.let { throw it }
        return registerResult
    }
}

internal fun sampleWarpProvisioningResult(): WarpProvisioningResult =
    WarpProvisioningResult(
        credentials =
            WarpCredentials(
                profileId = DefaultWarpProfileId,
                deviceId = "device-123",
                accessToken = listOf("access", "value", "provisioning").joinToString("-"),
                privateKey = "private-key",
                publicKey = "public-key",
            ),
        accountId = "account-123",
        accountType = "free",
        warpPlus = false,
        premiumData = 0L,
        quota = 0L,
        license = null,
        interfaceAddressV4 = "172.16.0.2/32",
        interfaceAddressV6 = "2606:4700:110:8a36::2/128",
        peerPublicKey = "peer-public-key",
        endpoint =
            WarpEndpointCacheEntry(
                networkScopeKey = "",
                host = "engage.cloudflareclient.com",
                ipv4 = "162.159.192.1",
                port = 2408,
                source = "registration",
            ),
        reservedBytes = byteArrayOf(1, 2, 3),
    )

internal class FakeWarpProfileStore : WarpProfileStore {
    private val profiles = linkedMapOf<String, WarpProfile>()
    private var activeProfileId: String? = null

    override suspend fun load(profileId: String): WarpProfile? = profiles[profileId]

    override suspend fun loadAll(): List<WarpProfile> = profiles.values.toList()

    override suspend fun save(profile: WarpProfile) {
        profiles[profile.id] = profile
    }

    override suspend fun remove(profileId: String) {
        profiles.remove(profileId)
    }

    override suspend fun activeProfileId(): String? = activeProfileId

    override suspend fun setActiveProfileId(profileId: String?) {
        activeProfileId = profileId
    }

    override suspend fun setActiveProfileIdOwned(
        profileId: String?,
        authority: com.poyka.ripdpi.data.PauseIntentAuthority,
        reference: com.poyka.ripdpi.data.PauseAuthorityRef,
        commandId: String,
    ): Boolean =
        authority.intentLinearizer.serialize {
            if (!(
                    authority.reference() == reference &&
                        authority.snapshotAuthority().command?.commandId == commandId
                )
            ) {
                false
            } else {
                activeProfileId =
                    profileId
                true
            }
        }

    override suspend fun clearAll() {
        profiles.clear()
        activeProfileId = null
    }
}

internal class FakeWarpCredentialStore : WarpCredentialStore {
    private val credentials = linkedMapOf<String, WarpCredentials>()
    var failNextSaveAfterWrite = false

    override suspend fun load(profileId: String): WarpCredentials? = credentials[profileId]

    override suspend fun loadAll(): List<WarpCredentials> = credentials.values.toList()

    override suspend fun save(
        profileId: String,
        credentials: WarpCredentials,
    ) {
        this.credentials[profileId] = credentials.copy(profileId = profileId)
        if (failNextSaveAfterWrite) {
            failNextSaveAfterWrite = false
            error("credential save failed after write")
        }
    }

    override suspend fun clear(profileId: String) {
        credentials.remove(profileId)
    }

    override suspend fun clearAll() {
        credentials.clear()
    }
}

internal class FakeWarpEndpointStore : WarpEndpointStore {
    private val entries = linkedMapOf<Pair<String, String>, WarpEndpointCacheEntry>()

    override suspend fun load(
        profileId: String,
        networkScopeKey: String,
    ): WarpEndpointCacheEntry? = entries[profileId to networkScopeKey]

    override suspend fun loadAll(profileId: String): List<WarpEndpointCacheEntry> =
        entries.filterKeys { (entryProfileId, _) -> entryProfileId == profileId }.values.toList()

    override suspend fun save(entry: WarpEndpointCacheEntry) {
        entries[entry.profileId to entry.networkScopeKey] = entry
    }

    override suspend fun clear(
        profileId: String,
        networkScopeKey: String,
    ) {
        entries.remove(profileId to networkScopeKey)
    }

    override suspend fun clearProfile(profileId: String) {
        entries.keys.filter { it.first == profileId }.forEach(entries::remove)
    }

    override suspend fun clearAll() {
        entries.clear()
    }
}

internal class FakeWarpEndpointProbe(
    private val responders: Map<String, Long> = emptyMap(),
) : WarpEndpointProbe {
    var calls: Int = 0
        private set

    override suspend fun probe(
        candidate: WarpEndpointCacheEntry,
        timeoutMillis: Int,
    ): WarpEndpointCacheEntry? {
        calls += 1
        val endpoint = candidate.ipv4 ?: candidate.ipv6 ?: candidate.host.orEmpty()
        val key = "$endpoint:${candidate.port}"
        val fallbackKey = endpoint
        val rttMs = responders[key] ?: responders[fallbackKey] ?: return null
        return candidate.copy(
            source = "scanner",
            rttMs = rttMs,
            updatedAtEpochMillis = 1L,
        )
    }
}
