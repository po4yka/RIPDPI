package com.poyka.ripdpi

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.CurrentSettingsMigrationLevel
import com.poyka.ripdpi.data.applySettingsCleanupMigration
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsCleanupMigrationCoordinator
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val rememberedPolicySource: DiagnosticsRememberedPolicySource,
    ) {
        suspend fun migrateIfNeeded() {
            val currentSettings = appSettingsRepository.snapshot()
            if (currentSettings.settingsMigrationLevel >= CurrentSettingsMigrationLevel) {
                return
            }

            val migratedSettings = currentSettings.applySettingsCleanupMigration()
            rememberedPolicySource.clearAll()
            if (migratedSettings != currentSettings) {
                appSettingsRepository.replace(migratedSettings)
            }
        }
    }
