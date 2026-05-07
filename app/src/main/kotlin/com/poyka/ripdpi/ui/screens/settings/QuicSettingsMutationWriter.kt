package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.data.normalizeQuicFakeHost

internal class QuicSettingsMutationWriter(
    update: (String, String, SettingsMutation) -> Unit,
) : AdvancedSettingsMutationWriter(update) {
    fun updateQuicFakeHost(value: String) {
        val normalized = normalizeQuicFakeHost(value)
        updateValue("quicFakeHost", normalized) {
            setQuicFakeHost(normalized)
        }
    }
}
