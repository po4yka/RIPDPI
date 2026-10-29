package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.applyToSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings

/** The strategy fields a replay changed: the value before, and the value the apply wrote. */
class ProxyStrategySnapshot(
    internal val original: AppSettings,
    internal val applied: AppSettings,
)

/**
 * Applies the strategy part of a native scan candidate's proxy preferences to app settings and puts
 * the original strategy back. Like the strategy probe's activator, it only writes app settings; it
 * does not signal the running proxy, and no settings-to-runtime reload signal exists to wait for.
 * The replay assumes the runtime picks up the strategy the same way the strategy probe does.
 */
interface ProxyConfigStrategyApplier {
    suspend fun apply(preferences: RipDpiProxyUIPreferences): ProxyStrategySnapshot

    suspend fun restore(snapshot: ProxyStrategySnapshot)
}

class SettingsProxyConfigStrategyApplier(
    private val appSettingsRepository: AppSettingsRepository,
) : ProxyConfigStrategyApplier {
    override suspend fun apply(preferences: RipDpiProxyUIPreferences): ProxyStrategySnapshot {
        lateinit var snapshot: ProxyStrategySnapshot
        // One update against the current value, so a concurrent write is not reverted.
        appSettingsRepository.update {
            val original = build()
            val written = preferences.applyToSettings(original)
            // Copy only strategy fields: listen, relay, WARP, AWG and runtime fields stay as they are.
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
            // Revert a field only if this replay wrote it and nobody changed it since.
            StrategySettingsSlots
                .filter { slot ->
                    slot.read(snapshot.applied) != slot.read(snapshot.original) &&
                        slot.read(current) == slot.read(snapshot.applied)
                }.forEach { slot -> slot.copy(this, snapshot.original) }
        }
    }
}
