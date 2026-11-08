package com.poyka.ripdpi.services

import androidx.annotation.Keep

/** JNI control-plane result. Package identity stays in the session-owned Kotlin map. */
@Keep
data class FlowOwnerResolution(
    @JvmField val uid: Int,
    @JvmField val appScope: String? = null,
) {
    override fun toString(): String = "FlowOwnerResolution(uid=[REDACTED], appScope=[REDACTED])"
}
