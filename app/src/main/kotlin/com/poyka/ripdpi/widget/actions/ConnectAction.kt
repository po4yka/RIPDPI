package com.poyka.ripdpi.widget.actions

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.poyka.ripdpi.data.Mode
import dagger.hilt.android.EntryPointAccessors

class ConnectAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val ep =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        val lastMode = ep.widgetStateRepository().snapshot().mode ?: Mode.VPN
        startServiceFromWidget(
            context = context,
            mode = lastMode,
            serviceStartPreflight = ep.serviceStartPreflight(),
            serviceController = ep.serviceController(),
        )
    }
}
