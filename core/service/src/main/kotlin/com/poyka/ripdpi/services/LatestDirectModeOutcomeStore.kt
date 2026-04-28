package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.LatestDirectModeOutcomeSnapshot
import com.poyka.ripdpi.data.LatestDirectModeOutcomeStore
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
class DefaultLatestDirectModeOutcomeStore
    @Inject
    constructor() : LatestDirectModeOutcomeStore {
        private val state = MutableStateFlow<LatestDirectModeOutcomeSnapshot?>(null)

        override val outcome: StateFlow<LatestDirectModeOutcomeSnapshot?> = state.asStateFlow()

        override fun publish(snapshot: LatestDirectModeOutcomeSnapshot?) {
            state.value = snapshot
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class LatestDirectModeOutcomeStoreModule {
    @Binds
    @Singleton
    abstract fun bindLatestDirectModeOutcomeStore(
        store: DefaultLatestDirectModeOutcomeStore,
    ): LatestDirectModeOutcomeStore
}
