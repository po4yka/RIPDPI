package com.poyka.ripdpi.widget.state

import android.content.Context
import com.poyka.ripdpi.data.WidgetSnapshot
import com.poyka.ripdpi.widget.actions.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

internal suspend fun loadSnapshot(context: Context): WidgetSnapshot =
    EntryPointAccessors
        .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        .widgetStateRepository()
        .snapshot()
