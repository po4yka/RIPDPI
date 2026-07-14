package com.poyka.ripdpi.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.poyka.ripdpi.data.WidgetNotifier
import com.poyka.ripdpi.data.WidgetUpdateTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : WidgetNotifier {
        override suspend fun pushUpdate(target: WidgetUpdateTarget) {
            when (target) {
                WidgetUpdateTarget.All -> {
                    ConnectToggleWidget().updateAll(context)
                    StatusDisplayWidget().updateAll(context)
                    TelemetryWidget().updateAll(context)
                    ModePickerWidget().updateAll(context)
                }

                WidgetUpdateTarget.Telemetry -> {
                    TelemetryWidget().updateAll(context)
                }
            }
        }
    }
