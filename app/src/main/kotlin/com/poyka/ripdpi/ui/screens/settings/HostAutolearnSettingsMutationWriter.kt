package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours

internal class HostAutolearnSettingsMutationWriter(
    update: (String, String, SettingsMutation) -> Unit,
) : AdvancedSettingsMutationWriter(update) {
    fun updateHostAutolearnPenaltyTtlHours(value: String) {
        value.toIntOrNull()?.let { ttl ->
            val normalized = normalizeHostAutolearnPenaltyTtlHours(ttl)
            updateValue("hostAutolearnPenaltyTtlHours", normalized.toString()) {
                setHostAutolearnPenaltyTtlHours(normalized)
            }
        }
    }

    fun updateHostAutolearnMaxHosts(value: String) {
        value.toIntOrNull()?.let { maxHosts ->
            val normalized = normalizeHostAutolearnMaxHosts(maxHosts)
            updateValue("hostAutolearnMaxHosts", normalized.toString()) {
                setHostAutolearnMaxHosts(normalized)
            }
        }
    }
}
