package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class MainSettingsDismissCoordinator
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) {
        fun dismissBatteryBanner(scope: CoroutineScope) {
            scope.launch {
                appSettingsRepository.update { setBatteryBannerDismissed(true) }
            }
        }

        fun dismissBackgroundGuidance(scope: CoroutineScope) {
            scope.launch {
                appSettingsRepository.update { setBackgroundGuidanceDismissed(true) }
            }
        }
    }
