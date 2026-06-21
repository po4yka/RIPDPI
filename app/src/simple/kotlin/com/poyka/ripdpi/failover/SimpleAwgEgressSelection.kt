package com.poyka.ripdpi.failover

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.services.AwgEgressSelectionSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimpleAwgEgressSelection
    @Inject
    constructor(
        private val awgProfileRepository: AwgProfileRepository,
        private val settingsRepository: AppSettingsRepository,
    ) : AwgEgressSelectionSource {
        override val selectionPriority: Int = 10

        private val selectedProfileId = MutableStateFlow<String?>(null)

        fun select(profileId: String) {
            selectedProfileId.value = profileId
        }

        fun clear() {
            selectedProfileId.value = null
        }

        override suspend fun selectedAwgEgress(): AwgActivationRequest? {
            val profileId =
                selectedProfileId.value
                    ?: settingsRepository
                        .snapshot()
                        .simpleFailoverAwgProfileId
                        .takeIf { it.isNotBlank() }
                        ?.also { selectedProfileId.value = it }
                    ?: return null
            return awgProfileRepository.load(profileId)?.request
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SimpleAwgEgressSelectionModule {
    @Binds
    @IntoSet
    @Singleton
    abstract fun bindAwgEgressSelectionSource(selection: SimpleAwgEgressSelection): AwgEgressSelectionSource
}
