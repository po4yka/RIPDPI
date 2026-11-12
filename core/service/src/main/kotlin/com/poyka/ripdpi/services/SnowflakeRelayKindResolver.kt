package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import javax.inject.Inject

internal class SnowflakeRelayKindResolver
    @Inject
    constructor() : RelayKindResolver {
        override fun supports(kind: String): Boolean = kind == RelayKindSnowflake

        override suspend fun resolve(request: RelayResolverRequest): RelayResolverResult =
            throw ServiceStartupRejectedException(
                FailureReason.RelayConfigRejected(
                    "Standalone Snowflake cannot relay application traffic. Use Tor with a Snowflake bridge.",
                ),
            )
    }
