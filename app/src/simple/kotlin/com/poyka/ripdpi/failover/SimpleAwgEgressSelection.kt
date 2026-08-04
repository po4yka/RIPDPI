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
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Selects a fully rehydrated AWG request for the simple flavor's startup fallback. */
interface SimpleAwgFallbackSelection {
    suspend fun firstAvailable(): AwgActivationRequest?

    fun select(request: AwgActivationRequest)
}

@Singleton
class SimpleAwgEgressSelection
    @Inject
    constructor(
        private val awgProfileRepository: AwgProfileRepository,
        private val settingsRepository: AppSettingsRepository,
    ) : AwgEgressSelectionSource,
        SimpleAwgFallbackSelection {
        override val selectionPriority: Int = 10

        private val selectedProfileId = MutableStateFlow<String?>(null)
        private val selectedRequest = MutableStateFlow<AwgActivationRequest?>(null)

        fun select(profileId: String) {
            selectedProfileId.value = profileId
            selectedRequest.value = null
        }

        override fun select(request: AwgActivationRequest) {
            selectedProfileId.value = request.profileId
            selectedRequest.value = request
        }

        fun clear() {
            selectedProfileId.value = null
            selectedRequest.value = null
        }

        override suspend fun firstAvailable(): AwgActivationRequest? =
            awgProfileRepository
                .observeProfiles()
                .first()
                .firstOrNull()
                ?.request

        override suspend fun selectedAwgEgress(): AwgActivationRequest? {
            selectedRequest.value?.let { return it }
            val profileId =
                selectedProfileId.value
                    ?: settingsRepository
                        .snapshot()
                        .simpleFailoverAwgProfileId
                        .takeIf { it.isNotBlank() }
                        ?.also { selectedProfileId.value = it }
                    ?: return null
            return awgProfileRepository.load(profileId)?.request?.also { selectedRequest.value = it }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SimpleAwgEgressSelectionModule {
    @Binds
    @Singleton
    abstract fun bindSimpleAwgFallbackSelection(selection: SimpleAwgEgressSelection): SimpleAwgFallbackSelection

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindAwgEgressSelectionSource(selection: SimpleAwgEgressSelection): AwgEgressSelectionSource
}
