package com.poyka.ripdpi.diagnostics

import android.content.Context
import android.telephony.SubscriptionManager

/** Captures settings without identifiers, network requests, or settings changes. */
class AndroidLocalNetworkContextCollector internal constructor(
    private val platform: AndroidLocalNetworkPlatform,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(AndroidLocalNetworkAdapter(context))

    fun capture(): LocalNetworkContextModel =
        LocalNetworkContextModel(
            capturedAt = clock().coerceAtLeast(0),
            transport = platform.transport(),
            device = platform.device(),
            sim = captureSim(),
        ).toSafeLocalNetworkContext()

    private fun captureSim(): LocalSimConstraints {
        val before = selection()
        if (before.scope != LocalSimScope.DEFAULT_DATA) return emptyLocalSim(before.scope)
        val evidence = platform.sim(before.id)
        val after = selection()
        return if (after.scope == LocalSimScope.DEFAULT_DATA && before.id == after.id) {
            evidence
        } else {
            // No part of a mixed-subscription observation may survive a selection change.
            LocalSimConstraints(scope = LocalSimScope.CHANGED)
        }
    }

    private fun selection(): AndroidLocalSubscription =
        try {
            val id = platform.defaultDataSubscriptionId()
            if (id >= 0 && id != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
                AndroidLocalSubscription(LocalSimScope.DEFAULT_DATA, id)
            } else {
                AndroidLocalSubscription(LocalSimScope.NOT_CONFIGURED)
            }
        } catch (_: SecurityException) {
            AndroidLocalSubscription(LocalSimScope.PERMISSION_DENIED)
        } catch (_: UnsupportedOperationException) {
            AndroidLocalSubscription(LocalSimScope.UNSUPPORTED)
        } catch (_: RuntimeException) {
            AndroidLocalSubscription(LocalSimScope.UNAVAILABLE)
        }
}

internal interface AndroidLocalNetworkPlatform {
    fun transport(): LocalTransport

    fun device(): LocalDeviceConstraints

    fun defaultDataSubscriptionId(): Int

    fun sim(subscriptionId: Int): LocalSimConstraints
}

private data class AndroidLocalSubscription(
    val scope: LocalSimScope,
    val id: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
)

internal inline fun <reified T : Enum<T>> localObservation(block: () -> T): T =
    try {
        block()
    } catch (_: SecurityException) {
        enumValueOf<T>("PERMISSION_DENIED")
    } catch (_: UnsupportedOperationException) {
        enumValueOf<T>("UNSUPPORTED")
    } catch (_: RuntimeException) {
        enumValueOf<T>("UNAVAILABLE")
    }

internal fun localBoolean(value: Boolean): LocalObservationState =
    if (value) LocalObservationState.ENABLED else LocalObservationState.DISABLED
