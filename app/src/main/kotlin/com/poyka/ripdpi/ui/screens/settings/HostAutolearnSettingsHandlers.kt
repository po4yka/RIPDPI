package com.poyka.ripdpi.ui.screens.settings

internal val hostAutolearnToggleHandlers: Map<AdvancedToggleSetting, HostAutolearnToggleHandler> =
    mapOf(
        AdvancedToggleSetting.HostAutolearnEnabled to
            { enabled -> updateBoolean("hostAutolearnEnabled", enabled) { setHostAutolearnEnabled(enabled) } },
        AdvancedToggleSetting.NetworkStrategyMemoryEnabled to
            { enabled ->
                updateBoolean("networkStrategyMemoryEnabled", enabled) {
                    setNetworkStrategyMemoryEnabled(enabled)
                }
            },
    )

internal val hostAutolearnTextHandlers: Map<AdvancedTextSetting, HostAutolearnTextHandler> =
    mapOf(
        AdvancedTextSetting.HostAutolearnPenaltyTtlHours to
            { value, _ -> updateHostAutolearnPenaltyTtlHours(value) },
        AdvancedTextSetting.HostAutolearnMaxHosts to
            { value, _ -> updateHostAutolearnMaxHosts(value) },
    )
