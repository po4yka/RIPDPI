package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingModeValidationRunner
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingValidationResult
import javax.inject.Inject

class OnboardingValidationCoordinator
    @Inject
    constructor(
        private val validationRunner: OnboardingModeValidationRunner,
    ) {
        suspend fun validate(
            mode: Mode,
            onProgress: (OnboardingValidationState) -> Unit,
        ): OnboardingValidationState {
            val result = validationRunner.validate(mode = mode, onProgress = onProgress)
            return result.toValidationState(mode)
        }

        fun retainActiveValidation() {
            validationRunner.retainActiveValidation()
        }

        fun stopActiveValidation() {
            validationRunner.stopActiveValidation()
        }

        private fun OnboardingValidationResult.toValidationState(mode: Mode): OnboardingValidationState =
            when (this) {
                is OnboardingValidationResult.Success -> {
                    OnboardingValidationState.Success(latencyMs = latencyMs, mode = mode)
                }

                is OnboardingValidationResult.Failed -> {
                    OnboardingValidationState.Failed(
                        reason = reason,
                        recoveryKind =
                            if (recoveryKind == OnboardingValidationRecoveryKind.RETRY && suggestedMode != null) {
                                OnboardingValidationRecoveryKind.SWITCH_MODE
                            } else {
                                recoveryKind
                            },
                        suggestedMode = suggestedMode,
                    )
                }
            }
    }
