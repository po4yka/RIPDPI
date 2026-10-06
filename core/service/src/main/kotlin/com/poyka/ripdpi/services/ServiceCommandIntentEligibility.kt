package com.poyka.ripdpi.services

import android.content.Intent
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction

/** Captured service commands must be rejected before an active native graph is attached. */
internal fun Intent?.hasStaleServiceCommand(arbiter: ServiceIntentArbiter): Boolean =
    this?.action in DurablyFencedServiceActions &&
        durableAuthorityReference()?.let(arbiter::isDurableCurrent) != true

internal fun isUserServiceStopAction(action: String?): Boolean =
    action == stopAction || action == notificationStopAction

private val DurablyFencedServiceActions =
    setOf(
        startAction,
        stopAction,
        transportActivationStartAction,
        diagnosticsStopAction,
        diagnosticsCompensatingStopAction,
    )
