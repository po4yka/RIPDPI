package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySource

internal val EmptyDestinationRoutingPolicySource =
    DestinationRoutingPolicySource {
        DestinationRoutingPolicySnapshot.Available(DestinationRoutingPolicy(canonicalDigest = ""))
    }
