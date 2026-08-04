package com.poyka.ripdpi.seed

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

/**
 * Session lifecycle watcher contract. Only the `simple` product-flavor source set
 * supplies a real implementation; every other flavor leaves
 * the [Optional] empty so the startup subsystem is a no-op.
 */
interface SimpleFlavorSessionWatcher {
    fun bind(scope: CoroutineScope)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SimpleFlavorSessionWatcherModule {
    @BindsOptionalOf
    abstract fun bindOptionalSimpleFlavorSessionWatcher(): SimpleFlavorSessionWatcher
}
