package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import java.util.UUID

/** Rebuilds a shareable NaiveProxy profile from a saved relay record; an empty password stays empty. */
internal fun toNaiveShareProfile(
    server: String,
    serverPort: Int,
    displayName: String,
    credentials: RelayCredentialRecord?,
): ProxyProfile? =
    credentials
        ?.naiveUsername
        ?.takeIf { it.isNotBlank() && server.isNotBlank() && serverPort > 0 }
        ?.let { username ->
            ProxyProfile.Naive(
                id = UUID.randomUUID().toString(),
                displayName = displayName,
                groupId = "",
                server = server,
                serverPort = serverPort,
                username = username,
                password = credentials.naivePassword.orEmpty(),
                extraHeaders = credentials.naiveExtraHeaders.orEmpty(),
                serverPinSha256 = credentials.naiveServerPinSha256.orEmpty(),
                quic = credentials.naiveQuic,
                concurrency = credentials.naiveConcurrency,
            )
        }
