package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NetworkAutoProfileBinding
import com.poyka.ripdpi.data.NetworkAutoProfileBindingStore
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.appSettingsFromJson
import com.poyka.ripdpi.data.toJson
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

private const val LogFingerprintHashLength = 12

data class NetworkAutoProfileApplyResult(
    val settings: AppSettings,
    val binding: NetworkAutoProfileBinding? = null,
    val applied: Boolean = false,
)

interface NetworkAutoProfileApplicator {
    suspend fun apply(
        settings: AppSettings,
        fingerprint: NetworkFingerprint?,
    ): NetworkAutoProfileApplyResult
}

@Singleton
class NetworkAutoProfileBindingApplier
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val bindingStore: NetworkAutoProfileBindingStore,
    ) : NetworkAutoProfileApplicator {
        suspend fun applyForCurrentNetwork(): NetworkAutoProfileApplyResult {
            val fingerprint = networkFingerprintProvider.capture()
            val settings = appSettingsRepository.snapshot()
            return apply(settings = settings, fingerprint = fingerprint)
        }

        override suspend fun apply(
            settings: AppSettings,
            fingerprint: NetworkFingerprint?,
        ): NetworkAutoProfileApplyResult {
            val fingerprintHash = fingerprint?.scopeKey()
            val result =
                if (fingerprintHash == null) {
                    NetworkAutoProfileApplyResult(settings = settings)
                } else {
                    resolveBinding(settings = settings, fingerprintHash = fingerprintHash)
                }
            if (result.applied) {
                appSettingsRepository.replace(result.settings)
                Logger.i {
                    "Applied network auto-profile binding ${result.binding?.id} for " +
                        fingerprintHash.orEmpty().take(LogFingerprintHashLength)
                }
            }
            return result
        }

        private suspend fun resolveBinding(
            settings: AppSettings,
            fingerprintHash: String,
        ): NetworkAutoProfileApplyResult {
            val binding = bindingStore.find(fingerprintHash)
            val boundSettings =
                binding?.let { candidate ->
                    runCatching { appSettingsFromJson(candidate.settingsJson) }
                        .onFailure { error ->
                            Logger.w(error) { "Ignoring malformed network auto-profile binding ${candidate.id}" }
                        }.getOrNull()
                }
            return when {
                binding == null -> {
                    NetworkAutoProfileApplyResult(settings = settings)
                }

                boundSettings == null -> {
                    NetworkAutoProfileApplyResult(settings = settings, binding = binding)
                }

                settings.toJson() == boundSettings.toJson() -> {
                    NetworkAutoProfileApplyResult(settings = settings, binding = binding)
                }

                else -> {
                    NetworkAutoProfileApplyResult(
                        settings = boundSettings,
                        binding = binding,
                        applied = true,
                    )
                }
            }
        }
    }

internal object NoOpNetworkAutoProfileApplicator : NetworkAutoProfileApplicator {
    override suspend fun apply(
        settings: AppSettings,
        fingerprint: NetworkFingerprint?,
    ): NetworkAutoProfileApplyResult = NetworkAutoProfileApplyResult(settings = settings)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkAutoProfileApplicatorModule {
    @Binds
    @Singleton
    abstract fun bindNetworkAutoProfileApplicator(
        applier: NetworkAutoProfileBindingApplier,
    ): NetworkAutoProfileApplicator
}
