package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpProfile

data class WarpEnrollmentSnapshot(
    val profile: WarpProfile,
    val credentials: WarpCredentials? = null,
    val endpoint: WarpEndpointCacheEntry? = null,
)

data class WarpZeroTrustImportRequest(
    val profileId: String = DefaultWarpProfileId,
    val displayName: String,
    val organization: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val privateKey: String? = null,
    val publicKey: String? = null,
    val peerPublicKey: String? = null,
    val interfaceAddressV4: String? = null,
    val interfaceAddressV6: String? = null,
)
