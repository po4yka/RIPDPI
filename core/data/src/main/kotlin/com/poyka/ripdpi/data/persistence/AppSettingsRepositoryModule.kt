package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.AppSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppSettingsRepositoryModule {
    @Binds
    internal abstract fun settings(store: RoomAppSettingsRepository): AppSettingsRepository
}
