package com.poyka.ripdpi.widget.actions

import com.poyka.ripdpi.data.WidgetStateRepository
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartPreflight
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun serviceController(): ServiceController

    fun serviceStartPreflight(): ServiceStartPreflight

    fun widgetStateRepository(): WidgetStateRepository
}
