package com.poyka.ripdpi.ui.screens.settings

internal val coreToggleHandlers: Map<AdvancedToggleSetting, CoreToggleHandler> =
    mapOf(
        AdvancedToggleSetting.UseCommandLine to
            { enabled ->
                updateBoolean("enableCmdSettings", enabled) { setEnableCmdSettings(enabled) }
            },
        AdvancedToggleSetting.DiagnosticsMonitorEnabled to
            { enabled ->
                updateBoolean("diagnosticsMonitorEnabled", enabled) {
                    setDiagnosticsMonitorEnabled(enabled)
                }
            },
        AdvancedToggleSetting.DiagnosticsExportIncludeHistory to
            { enabled ->
                updateBoolean("diagnosticsExportIncludeHistory", enabled) {
                    setDiagnosticsExportIncludeHistory(enabled)
                }
            },
        AdvancedToggleSetting.StrategyPackAllowRollbackOverride to
            { enabled ->
                updateBoolean("strategyPackAllowRollbackOverride", enabled) {
                    setStrategyPackAllowRollbackOverride(enabled)
                }
            },
        AdvancedToggleSetting.NoDomain to
            { enabled -> updateBoolean("noDomain", enabled) { setNoDomain(enabled) } },
        AdvancedToggleSetting.TcpFastOpen to
            { enabled -> updateBoolean("tcpFastOpen", enabled) { setTcpFastOpen(enabled) } },
        AdvancedToggleSetting.PcapCaptureEnabled to
            { enabled -> updateBoolean("pcapCaptureEnabled", enabled) { setPcapCaptureEnabled(enabled) } },
        AdvancedToggleSetting.WsTunnelAllowInsecureSni to
            { enabled ->
                updateBoolean("wsTunnelAllowInsecureSni", enabled) {
                    setWsTunnelAllowInsecureSni(enabled)
                }
            },
    )

internal val coreTextHandlers: Map<AdvancedTextSetting, CoreTextHandler> =
    mapOf(
        AdvancedTextSetting.DiagnosticsSampleIntervalSeconds to
            { value, _ ->
                updateIntValue("diagnosticsSampleIntervalSeconds", value) { intervalSeconds ->
                    { setDiagnosticsSampleIntervalSeconds(intervalSeconds) }
                }
            },
        AdvancedTextSetting.DiagnosticsHistoryRetentionDays to
            { value, _ ->
                updateIntValue("diagnosticsHistoryRetentionDays", value) { retentionDays ->
                    { setDiagnosticsHistoryRetentionDays(retentionDays) }
                }
            },
        AdvancedTextSetting.CommandLineArgs to { value, _ -> updateValue("cmdArgs", value) { setCmdArgs(value) } },
        AdvancedTextSetting.ProxyIp to { value, _ -> updateValue("proxyIp", value) { setProxyIp(value) } },
        AdvancedTextSetting.ProxyPort to
            { value, _ -> updateIntValue("proxyPort", value) { port -> { setProxyPort(port) } } },
        AdvancedTextSetting.MaxConnections to
            { value, _ ->
                updateIntValue("maxConnections", value) { maxConnections ->
                    { setMaxConnections(maxConnections) }
                }
            },
        AdvancedTextSetting.BufferSize to
            { value, _ -> updateIntValue("bufferSize", value) { bufferSize -> { setBufferSize(bufferSize) } } },
        AdvancedTextSetting.HostsBlacklist to
            { value, _ -> updateValue("hostsBlacklist", value) { setHostsBlacklist(value) } },
        AdvancedTextSetting.HostsWhitelist to
            { value, _ -> updateValue("hostsWhitelist", value) { setHostsWhitelist(value) } },
    )

internal val coreOptionHandlers: Map<AdvancedOptionSetting, CoreOptionHandler> =
    mapOf(
        AdvancedOptionSetting.HostsMode to
            { value, _ -> updateValue("hostsMode", value) { setHostsMode(value) } },
    )
