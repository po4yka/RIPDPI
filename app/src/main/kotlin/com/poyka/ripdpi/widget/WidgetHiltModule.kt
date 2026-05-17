package com.poyka.ripdpi.widget

import com.poyka.ripdpi.data.WidgetNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetHiltModule {
    @Binds
    @Singleton
    abstract fun bindWidgetNotifier(impl: WidgetUpdater): WidgetNotifier
}
