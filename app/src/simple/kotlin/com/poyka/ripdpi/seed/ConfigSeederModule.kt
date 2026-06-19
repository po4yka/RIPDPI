package com.poyka.ripdpi.seed

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConfigSeederModule {
    @Binds
    @Singleton
    abstract fun bindSimpleFlavorSeeder(seeder: ConfigSeeder): SimpleFlavorSeeder
}
