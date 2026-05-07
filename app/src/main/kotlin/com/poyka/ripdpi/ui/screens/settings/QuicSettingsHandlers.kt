package com.poyka.ripdpi.ui.screens.settings

internal val quicToggleHandlers: Map<AdvancedToggleSetting, QuicToggleHandler> =
    mapOf(
        AdvancedToggleSetting.QuicSupportV1 to
            { enabled -> updateBoolean("quicSupportV1", enabled) { setQuicSupportV1(enabled) } },
        AdvancedToggleSetting.QuicSupportV2 to
            { enabled -> updateBoolean("quicSupportV2", enabled) { setQuicSupportV2(enabled) } },
        AdvancedToggleSetting.QuicBindLowPort to
            { enabled -> updateBoolean("quicBindLowPort", enabled) { setQuicBindLowPort(enabled) } },
        AdvancedToggleSetting.QuicMigrateAfterHandshake to
            { enabled ->
                updateBoolean("quicMigrateAfterHandshake", enabled) {
                    setQuicMigrateAfterHandshake(enabled)
                }
            },
    )

internal val quicTextHandlers: Map<AdvancedTextSetting, QuicTextHandler> =
    mapOf(
        AdvancedTextSetting.QuicFakeHost to { value, _ -> updateQuicFakeHost(value) },
    )

internal val quicOptionHandlers: Map<AdvancedOptionSetting, QuicOptionHandler> =
    mapOf(
        AdvancedOptionSetting.QuicInitialMode to
            { value, _ -> updateValue("quicInitialMode", value) { setQuicInitialMode(value) } },
        AdvancedOptionSetting.QuicFakeProfile to
            { value, _ -> updateValue("quicFakeProfile", value) { setQuicFakeProfile(value) } },
    )
