package com.poyka.ripdpi.widget.actions

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.ServiceStartResult
import dagger.hilt.android.EntryPointAccessors

val ModeKey = ActionParameters.Key<String>("mode")

class PickModeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val modeName = parameters[ModeKey] ?: return
        val mode =
            runCatching { Mode.fromString(modeName) }.getOrNull() ?: return
        val ep =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        when (ep.serviceController().start(mode)) {
            is ServiceStartResult.Accepted -> Unit
            is ServiceStartResult.Rejected -> Unit
        }
    }
}
