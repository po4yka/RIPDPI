package com.poyka.ripdpi.data.support

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import javax.inject.Inject

data class SupportSettingsPreview(
    val title: String,
    val reason: String,
    val changes: List<SupportSettingsFieldChange>,
    val restartPolicy: String,
) {
    val hasSensitiveChanges: Boolean
        get() = changes.any { it.sensitivity == SupportSettingsSensitivity.Sensitive }
}

sealed interface SupportSettingsPreviewResult {
    data class Ready(
        val preview: SupportSettingsPreview,
    ) : SupportSettingsPreviewResult

    data class Invalid(
        val errors: List<SupportSettingsFieldResult.Error>,
    ) : SupportSettingsPreviewResult
}

sealed interface SupportSettingsApplyResult {
    data class Success(
        val changes: List<SupportSettingsFieldChange>,
        val restartPolicy: String,
    ) : SupportSettingsApplyResult

    data class Invalid(
        val errors: List<SupportSettingsFieldResult.Error>,
    ) : SupportSettingsApplyResult
}

class SupportSettingsApplyUseCase
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) {
        suspend fun preview(packageJson: String): SupportSettingsPreviewResult {
            val pkg =
                when (val decoded = SupportSettingsPackageCodec.decode(packageJson)) {
                    is SupportSettingsPackageDecodeResult.Success -> {
                        decoded.value
                    }

                    is SupportSettingsPackageDecodeResult.Error -> {
                        return SupportSettingsPreviewResult.Invalid(
                            listOf(
                                SupportSettingsFieldResult.Error(
                                    path = "",
                                    reason = SupportSettingsFieldErrorReason.InvalidValue,
                                ),
                            ),
                        )
                    }
                }
            return preview(appSettingsRepository.snapshot(), pkg)
        }

        fun preview(
            current: AppSettings,
            pkg: SupportSettingsPackage,
        ): SupportSettingsPreviewResult =
            when (val staged = stage(current, pkg)) {
                is StagedSupportSettings.Invalid -> {
                    SupportSettingsPreviewResult.Invalid(staged.errors)
                }

                is StagedSupportSettings.Ready -> {
                    SupportSettingsPreviewResult.Ready(
                        SupportSettingsPreview(
                            title = pkg.title,
                            reason = pkg.reason,
                            changes = staged.changes,
                            restartPolicy = pkg.restartPolicy,
                        ),
                    )
                }
            }

        suspend fun apply(packageJson: String): SupportSettingsApplyResult {
            val pkg =
                when (val decoded = SupportSettingsPackageCodec.decode(packageJson)) {
                    is SupportSettingsPackageDecodeResult.Success -> {
                        decoded.value
                    }

                    is SupportSettingsPackageDecodeResult.Error -> {
                        return SupportSettingsApplyResult.Invalid(
                            listOf(
                                SupportSettingsFieldResult.Error(
                                    path = "",
                                    reason = SupportSettingsFieldErrorReason.InvalidValue,
                                ),
                            ),
                        )
                    }
                }
            var result: SupportSettingsApplyResult? = null
            appSettingsRepository.update {
                result =
                    when (val staged = stage(build(), pkg)) {
                        is StagedSupportSettings.Invalid -> {
                            SupportSettingsApplyResult.Invalid(staged.errors)
                        }

                        is StagedSupportSettings.Ready -> {
                            clear().mergeFrom(staged.settings)
                            SupportSettingsApplyResult.Success(staged.changes, pkg.restartPolicy)
                        }
                    }
            }
            return checkNotNull(result)
        }

        private fun stage(
            current: AppSettings,
            pkg: SupportSettingsPackage,
        ): StagedSupportSettings {
            val builder = current.toBuilder()
            val changes = mutableListOf<SupportSettingsFieldChange>()
            val errors = mutableListOf<SupportSettingsFieldResult.Error>()
            pkg.operations.forEach { operation ->
                when (val result = SupportSettingsFieldRegistry.apply(builder, operation)) {
                    is SupportSettingsFieldResult.Error -> errors += result
                    is SupportSettingsFieldResult.Success -> changes += result.change
                }
            }
            if (errors.isNotEmpty()) return StagedSupportSettings.Invalid(errors)
            return StagedSupportSettings.Ready(builder.build(), changes)
        }
    }

private sealed interface StagedSupportSettings {
    data class Ready(
        val settings: AppSettings,
        val changes: List<SupportSettingsFieldChange>,
    ) : StagedSupportSettings

    data class Invalid(
        val errors: List<SupportSettingsFieldResult.Error>,
    ) : StagedSupportSettings
}
