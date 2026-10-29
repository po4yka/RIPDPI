package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.applyToSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.diagnostics.StrategyProbeActivationSnapshot

/** Applies a native scan candidate's proxy preferences to app settings and puts the original back. */
interface ProxyConfigStrategyApplier {
    suspend fun capture(): StrategyProbeActivationSnapshot

    suspend fun apply(preferences: RipDpiProxyUIPreferences)

    suspend fun restore(snapshot: StrategyProbeActivationSnapshot)
}

class SettingsProxyConfigStrategyApplier(
    private val appSettingsRepository: AppSettingsRepository,
) : ProxyConfigStrategyApplier {
    override suspend fun capture(): StrategyProbeActivationSnapshot =
        StrategyProbeActivationSnapshot(snapshotId = "app-settings", settings = appSettingsRepository.snapshot())

    override suspend fun apply(preferences: RipDpiProxyUIPreferences) {
        // Same rules as the recommendation apply: one update against the current value, autolearn kept.
        appSettingsRepository.update {
            val before = build()
            clear().mergeFrom(preferences.applyToSettings(before).withAutolearnFrom(before))
        }
    }

    override suspend fun restore(snapshot: StrategyProbeActivationSnapshot) {
        val original = snapshot.settings ?: return
        // apply() can write any proxy field, so put the whole captured value back.
        appSettingsRepository.update {
            val current = build()
            clear().mergeFrom(original.withAutolearnFrom(current))
        }
    }
}

private fun com.poyka.ripdpi.proto.AppSettings.withAutolearnFrom(
    source: com.poyka.ripdpi.proto.AppSettings,
): com.poyka.ripdpi.proto.AppSettings =
    toBuilder()
        .setHostAutolearnEnabled(source.hostAutolearnEnabled)
        .setHostAutolearnPenaltyTtlHours(source.hostAutolearnPenaltyTtlHours)
        .setHostAutolearnMaxHosts(source.hostAutolearnMaxHosts)
        .build()
