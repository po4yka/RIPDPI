package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.data.normalizeAdaptiveFallbackCachePrefixV4
import com.poyka.ripdpi.data.normalizeAdaptiveFallbackCacheTtlSeconds

internal class AdaptiveFallbackSettingsMutationWriter(
    update: (String, String, SettingsMutation) -> Unit,
) : AdvancedSettingsMutationWriter(update) {
    fun updateAdaptiveFallbackCacheTtlSeconds(value: String) {
        value.toIntOrNull()?.let { ttl ->
            val normalized = normalizeAdaptiveFallbackCacheTtlSeconds(ttl)
            updateValue("adaptiveFallbackCacheTtlSeconds", normalized.toString()) {
                setAdaptiveFallbackCacheTtlSeconds(normalized)
            }
        }
    }

    fun updateAdaptiveFallbackCachePrefixV4(value: String) {
        value.toIntOrNull()?.let { prefix ->
            val normalized = normalizeAdaptiveFallbackCachePrefixV4(prefix)
            updateValue("adaptiveFallbackCachePrefixV4", normalized.toString()) {
                setAdaptiveFallbackCachePrefixV4(normalized)
            }
        }
    }
}
