package com.poyka.ripdpi.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.poyka.ripdpi.data.WidgetNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdater
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WidgetNotifier {
        override suspend fun pushUpdate() {
            ConnectToggleWidget().updateAll(context)
            StatusDisplayWidget().updateAll(context)
            TelemetryWidget().updateAll(context)
            ModePickerWidget().updateAll(context)
        }
    }
