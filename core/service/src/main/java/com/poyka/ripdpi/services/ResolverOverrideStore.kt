package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.TemporaryResolverOverride
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultResolverOverrideStore
    @Inject
    constructor() : ResolverOverrideStore {
        private val state = MutableStateFlow<TemporaryResolverOverride?>(null)

        override val override: StateFlow<TemporaryResolverOverride?> = state.asStateFlow()

        override fun setTemporaryOverride(override: TemporaryResolverOverride) {
            state.value = override
        }

        override fun clear() {
            state.value = null
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ResolverOverrideStoreModule {
    @Binds
    @Singleton
    abstract fun bindResolverOverrideStore(store: DefaultResolverOverrideStore): ResolverOverrideStore
}
