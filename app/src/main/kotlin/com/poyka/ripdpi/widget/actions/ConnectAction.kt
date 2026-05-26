package com.poyka.ripdpi.widget.actions

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.ServiceStartResult
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
        when (ep.serviceController().start(lastMode)) {
            is ServiceStartResult.Accepted -> Unit
            is ServiceStartResult.Rejected -> Unit
        }
    }
}
