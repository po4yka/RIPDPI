package com.poyka.ripdpi.widget.actions

import android.content.Context
import android.content.Intent
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartPreflight
import com.poyka.ripdpi.services.ServiceStartPreflightResult
import com.poyka.ripdpi.services.ServiceStartResult

internal suspend fun startServiceFromWidget(
    context: Context,
    mode: Mode,
    serviceStartPreflight: ServiceStartPreflight,
    serviceController: ServiceController,
) {
    if (serviceStartPreflight.check(mode) == ServiceStartPreflightResult.LocalNetworkPermissionRequired) {
        launchWidgetStartRecovery(context, requestConfiguredStart = true)
        return
    }
    handleWidgetStartResult(context, serviceController.start(mode))
}

internal fun handleWidgetStartResult(
    context: Context,
    result: ServiceStartResult,
) {
    if (result is ServiceStartResult.Rejected) {
        launchWidgetStartRecovery(context, requestConfiguredStart = false)
    }
}

private fun launchWidgetStartRecovery(
    context: Context,
    requestConfiguredStart: Boolean,
) {
    context.startActivity(
        MainActivity
            .createLaunchIntent(
                context,
                openHome = true,
                requestStartConfiguredMode = requestConfiguredStart,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
