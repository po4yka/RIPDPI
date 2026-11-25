package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.applyToSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings

class ProxyStrategySnapshot(
    internal val original: AppSettings,
    internal val applied: AppSettings,
)

interface ProxyConfigStrategyApplier {
    suspend fun apply(preferences: RipDpiProxyUIPreferences): ProxyStrategySnapshot

    suspend fun restore(snapshot: ProxyStrategySnapshot)
}

class SettingsProxyConfigStrategyApplier(
    private val appSettingsRepository: AppSettingsRepository,
) : ProxyConfigStrategyApplier {
    override suspend fun apply(preferences: RipDpiProxyUIPreferences): ProxyStrategySnapshot {
        lateinit var snapshot: ProxyStrategySnapshot
        appSettingsRepository.update {
            val original = build()
            val written = preferences.applyToSettings(original)
            val applied =
                original
                    .toBuilder()
                    .also { b -> StrategySettingsSlots.forEach { it.copy(b, written) } }
                    .build()
            clear().mergeFrom(applied)
            snapshot = ProxyStrategySnapshot(original = original, applied = applied)
        }
        return snapshot
    }

    override suspend fun restore(snapshot: ProxyStrategySnapshot) {
        appSettingsRepository.update {
            val current = build()
            StrategySettingsSlots
                .filter { slot ->
                    slot.read(snapshot.applied) != slot.read(snapshot.original) &&
                        slot.read(current) == slot.read(snapshot.applied)
                }.forEach { slot -> slot.copy(this, snapshot.original) }
        }
    }
}
