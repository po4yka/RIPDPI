package com.poyka.ripdpi.ui.screens.settings

internal val warpToggleHandlers: Map<AdvancedToggleSetting, WarpToggleHandler> =
    mapOf(
        AdvancedToggleSetting.WarpEnabled to
            { enabled -> updateBoolean("warpEnabled", enabled) { setWarpEnabled(enabled) } },
        AdvancedToggleSetting.WarpBuiltInRulesEnabled to
            { enabled ->
                updateBoolean("warpBuiltinRulesEnabled", enabled) {
                    setWarpBuiltinRulesEnabled(enabled)
                }
            },
        AdvancedToggleSetting.WarpScannerEnabled to
            { enabled -> updateBoolean("warpScannerEnabled", enabled) { setWarpScannerEnabled(enabled) } },
        AdvancedToggleSetting.WarpAmneziaEnabled to
            { enabled -> updateBoolean("warpAmneziaEnabled", enabled) { setWarpAmneziaEnabled(enabled) } },
    )

internal val warpTextHandlers: Map<AdvancedTextSetting, WarpTextHandler> =
    mapOf(
        AdvancedTextSetting.WarpRouteHosts to
            { value, _ -> updateValue("warpRouteHosts", value) { setWarpRouteHosts(value) } },
        AdvancedTextSetting.WarpManualEndpointHost to
            { value, _ -> updateValue("warpManualEndpointHost", value) { setWarpManualEndpointHost(value) } },
        AdvancedTextSetting.WarpManualEndpointIpv4 to
            { value, _ -> updateValue("warpManualEndpointV4", value) { setWarpManualEndpointV4(value) } },
        AdvancedTextSetting.WarpManualEndpointIpv6 to
            { value, _ -> updateValue("warpManualEndpointV6", value) { setWarpManualEndpointV6(value) } },
        AdvancedTextSetting.WarpManualEndpointPort to
            { value, _ ->
                updateIntValue("warpManualEndpointPort", value) { port ->
                    { setWarpManualEndpointPort(port.coerceIn(1, MaxWarpEndpointPort)) }
                }
            },
        AdvancedTextSetting.WarpScannerParallelism to
            { value, _ ->
                updateIntValue("warpScannerParallelism", value) { parallelism ->
                    { setWarpScannerParallelism(parallelism.coerceAtLeast(1)) }
                }
            },
        AdvancedTextSetting.WarpScannerMaxRttMs to
            { value, _ ->
                updateIntValue("warpScannerMaxRttMs", value) { maxRttMs ->
                    { setWarpScannerMaxRttMs(maxRttMs.coerceAtLeast(1)) }
                }
            },
        AdvancedTextSetting.WarpAmneziaJc to
            { value, _ -> updateIntValue("warpAmneziaJc", value) { jc -> { setWarpAmneziaJc(jc) } } },
        AdvancedTextSetting.WarpAmneziaJmin to
            { value, _ -> updateIntValue("warpAmneziaJmin", value) { jmin -> { setWarpAmneziaJmin(jmin) } } },
        AdvancedTextSetting.WarpAmneziaJmax to
            { value, _ -> updateIntValue("warpAmneziaJmax", value) { jmax -> { setWarpAmneziaJmax(jmax) } } },
        AdvancedTextSetting.WarpAmneziaH1 to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    updateValue("warpAmneziaH1", parsed.toString()) { setWarpAmneziaH1(parsed) }
                }
            },
        AdvancedTextSetting.WarpAmneziaH2 to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    updateValue("warpAmneziaH2", parsed.toString()) { setWarpAmneziaH2(parsed) }
                }
            },
        AdvancedTextSetting.WarpAmneziaH3 to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    updateValue("warpAmneziaH3", parsed.toString()) { setWarpAmneziaH3(parsed) }
                }
            },
        AdvancedTextSetting.WarpAmneziaH4 to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    updateValue("warpAmneziaH4", parsed.toString()) { setWarpAmneziaH4(parsed) }
                }
            },
        AdvancedTextSetting.WarpAmneziaS1 to
            { value, _ -> updateIntValue("warpAmneziaS1", value) { s1 -> { setWarpAmneziaS1(s1) } } },
        AdvancedTextSetting.WarpAmneziaS2 to
            { value, _ -> updateIntValue("warpAmneziaS2", value) { s2 -> { setWarpAmneziaS2(s2) } } },
        AdvancedTextSetting.WarpAmneziaS3 to
            { value, _ -> updateIntValue("warpAmneziaS3", value) { s3 -> { setWarpAmneziaS3(s3) } } },
        AdvancedTextSetting.WarpAmneziaS4 to
            { value, _ -> updateIntValue("warpAmneziaS4", value) { s4 -> { setWarpAmneziaS4(s4) } } },
    )

internal val warpOptionHandlers: Map<AdvancedOptionSetting, WarpOptionHandler> =
    mapOf(
        AdvancedOptionSetting.WarpRouteMode to
            { value, _ -> updateWarpRouteMode(value) },
        AdvancedOptionSetting.WarpEndpointSelectionMode to
            { value, _ -> updateWarpEndpointSelectionMode(value) },
        AdvancedOptionSetting.WarpAmneziaPreset to
            { value, uiState -> updateWarpAmneziaPreset(value, uiState) },
    )
