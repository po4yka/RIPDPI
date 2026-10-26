package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.data.wireguard.AmneziaWgWireProfile

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
            { value, _ ->
                if (warpAmneziaInputIsValid(AdvancedTextSetting.WarpAmneziaJc, value)) {
                    updateIntValue("warpAmneziaJc", value) { jc -> { setWarpAmneziaJc(jc) } }
                }
            },
        AdvancedTextSetting.WarpAmneziaJmin to
            { value, _ ->
                if (warpAmneziaInputIsValid(AdvancedTextSetting.WarpAmneziaJmin, value)) {
                    updateIntValue("warpAmneziaJmin", value) { jmin -> { setWarpAmneziaJmin(jmin) } }
                }
            },
        AdvancedTextSetting.WarpAmneziaJmax to
            { value, _ ->
                if (warpAmneziaInputIsValid(AdvancedTextSetting.WarpAmneziaJmax, value)) {
                    updateIntValue("warpAmneziaJmax", value) { jmax -> { setWarpAmneziaJmax(jmax) } }
                }
            },
        AdvancedTextSetting.WarpAmneziaH1 to
            { value, _ ->
                if (AmneziaWgWireProfile.headerIsValid(value, AmneziaWgWireProfile.InitiationType)) {
                    updateValue("warpAmneziaH1", value) { setWarpAmneziaH1(value) }
                }
            },
        AdvancedTextSetting.WarpAmneziaH2 to
            { value, _ ->
                if (AmneziaWgWireProfile.headerIsValid(value, AmneziaWgWireProfile.ResponseType)) {
                    updateValue("warpAmneziaH2", value) { setWarpAmneziaH2(value) }
                }
            },
        AdvancedTextSetting.WarpAmneziaH3 to
            { value, _ ->
                if (AmneziaWgWireProfile.headerIsValid(value, AmneziaWgWireProfile.CookieType)) {
                    updateValue("warpAmneziaH3", value) { setWarpAmneziaH3(value) }
                }
            },
        AdvancedTextSetting.WarpAmneziaH4 to
            { value, _ ->
                if (AmneziaWgWireProfile.headerIsValid(value, AmneziaWgWireProfile.TransportType)) {
                    updateValue("warpAmneziaH4", value) { setWarpAmneziaH4(value) }
                }
            },
        AdvancedTextSetting.WarpAmneziaS1 to
            { value, _ ->
                if (warpAmneziaInputIsValid(AdvancedTextSetting.WarpAmneziaS1, value)) {
                    updateIntValue("warpAmneziaS1", value) { s1 -> { setWarpAmneziaS1(s1) } }
                }
            },
        AdvancedTextSetting.WarpAmneziaS2 to
            { value, _ ->
                if (warpAmneziaInputIsValid(AdvancedTextSetting.WarpAmneziaS2, value)) {
                    updateIntValue("warpAmneziaS2", value) { s2 -> { setWarpAmneziaS2(s2) } }
                }
            },
        AdvancedTextSetting.WarpAmneziaS3 to
            { value, _ ->
                if (warpAmneziaInputIsValid(AdvancedTextSetting.WarpAmneziaS3, value)) {
                    updateIntValue("warpAmneziaS3", value) { s3 -> { setWarpAmneziaS3(s3) } }
                }
            },
        AdvancedTextSetting.WarpAmneziaS4 to
            { value, _ ->
                if (warpAmneziaInputIsValid(AdvancedTextSetting.WarpAmneziaS4, value)) {
                    updateIntValue("warpAmneziaS4", value) { s4 -> { setWarpAmneziaS4(s4) } }
                }
            },
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

internal fun warpAmneziaInputIsValid(
    setting: AdvancedTextSetting,
    value: String,
): Boolean =
    when (setting) {
        AdvancedTextSetting.WarpAmneziaH1 -> {
            AmneziaWgWireProfile.headerIsValid(value, AmneziaWgWireProfile.InitiationType)
        }

        AdvancedTextSetting.WarpAmneziaH2 -> {
            AmneziaWgWireProfile.headerIsValid(value, AmneziaWgWireProfile.ResponseType)
        }

        AdvancedTextSetting.WarpAmneziaH3 -> {
            AmneziaWgWireProfile.headerIsValid(value, AmneziaWgWireProfile.CookieType)
        }

        AdvancedTextSetting.WarpAmneziaH4 -> {
            AmneziaWgWireProfile.headerIsValid(value, AmneziaWgWireProfile.TransportType)
        }

        AdvancedTextSetting.WarpAmneziaJc -> {
            value.toIntOrNull()?.let { it in 0..AmneziaWgWireProfile.MaximumJunkPackets } ==
                true
        }

        AdvancedTextSetting.WarpAmneziaJmin,
        AdvancedTextSetting.WarpAmneziaJmax,
        AdvancedTextSetting.WarpAmneziaS1,
        AdvancedTextSetting.WarpAmneziaS2,
        AdvancedTextSetting.WarpAmneziaS3,
        AdvancedTextSetting.WarpAmneziaS4,
        -> {
            value.toIntOrNull()?.let { it in 0..AmneziaWgWireProfile.MaximumPacket } == true
        }

        else -> {
            false
        }
    }
