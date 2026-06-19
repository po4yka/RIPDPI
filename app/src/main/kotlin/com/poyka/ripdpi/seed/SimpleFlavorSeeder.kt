package com.poyka.ripdpi.seed

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * First-launch config seeder contract. Only the `simple` product-flavor
 * source set supplies a real implementation; every other flavor leaves
 * the [Optional] empty so the startup subsystem is a no-op.
 */
interface SimpleFlavorSeeder {
    suspend fun seed()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SimpleFlavorSeederModule {
    @BindsOptionalOf
    abstract fun bindOptionalSimpleFlavorSeeder(): SimpleFlavorSeeder
}
