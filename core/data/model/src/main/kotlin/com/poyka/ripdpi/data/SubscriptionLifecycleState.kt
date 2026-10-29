package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

/** Lifecycle state of a remote subscription. */
@Serializable
enum class SubscriptionLifecycleState {
    ACTIVE,
    EXPIRED,
    SUSPENDED,
    UNAVAILABLE,
    UNKNOWN,
}
