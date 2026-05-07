package com.poyka.ripdpi.ui.screens.settings

internal val adaptiveFallbackToggleHandlers: Map<AdvancedToggleSetting, AdaptiveFallbackToggleHandler> =
    mapOf(
        AdvancedToggleSetting.AdaptiveFallbackEnabled to
            { enabled -> updateBoolean("adaptiveFallbackEnabled", enabled) { setAdaptiveFallbackEnabled(enabled) } },
        AdvancedToggleSetting.AdaptiveFallbackTorst to
            { enabled -> updateBoolean("adaptiveFallbackTorst", enabled) { setAdaptiveFallbackTorst(enabled) } },
        AdvancedToggleSetting.AdaptiveFallbackTlsErr to
            { enabled -> updateBoolean("adaptiveFallbackTlsErr", enabled) { setAdaptiveFallbackTlsErr(enabled) } },
        AdvancedToggleSetting.AdaptiveFallbackHttpRedirect to
            { enabled ->
                updateBoolean("adaptiveFallbackHttpRedirect", enabled) {
                    setAdaptiveFallbackHttpRedirect(enabled)
                }
            },
        AdvancedToggleSetting.AdaptiveFallbackConnectFailure to
            { enabled ->
                updateBoolean("adaptiveFallbackConnectFailure", enabled) {
                    setAdaptiveFallbackConnectFailure(enabled)
                }
            },
        AdvancedToggleSetting.AdaptiveFallbackAutoSort to
            { enabled -> updateBoolean("adaptiveFallbackAutoSort", enabled) { setAdaptiveFallbackAutoSort(enabled) } },
    )

internal val adaptiveFallbackTextHandlers: Map<AdvancedTextSetting, AdaptiveFallbackTextHandler> =
    mapOf(
        AdvancedTextSetting.AdaptiveFallbackCacheTtlSeconds to
            { value, _ -> updateAdaptiveFallbackCacheTtlSeconds(value) },
        AdvancedTextSetting.AdaptiveFallbackCachePrefixV4 to
            { value, _ -> updateAdaptiveFallbackCachePrefixV4(value) },
    )
