package com.poyka.ripdpi.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileMutationGenerationPublisher
    @Inject
    constructor() : ProfileMutationGenerationSource {
        private val state = MutableStateFlow(0L)
        override val generation = state.asStateFlow()

        internal fun completed() {
            state.update { Math.addExact(it, 1L) }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileMutationGenerationModule {
    @Binds
    abstract fun bindProfileMutationGenerationSource(
        publisher: ProfileMutationGenerationPublisher,
    ): ProfileMutationGenerationSource
}
