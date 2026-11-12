package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import javax.inject.Inject

internal class LocalPathRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindWebTunnel || kind == RelayKindObfs4

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult {
            if (request.mergedConfig.kind == RelayKindObfs4) {
                throw ServiceStartupRejectedException(
                    FailureReason.RelayConfigRejected(
                        "Standalone obfs4 cannot relay application traffic. Use Tor with an obfs4 bridge.",
                    ),
                )
            }
            throw ServiceStartupRejectedException(
                FailureReason.RelayConfigRejected(
                    "Standalone WebTunnel cannot relay application traffic. Use Tor with a WebTunnel bridge.",
                ),
            )
        }
    }
