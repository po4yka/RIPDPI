package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentMapOf

@Composable
internal fun ModeEditorScreenWithNoOpCallbacks(
    uiState: ConnectionUiState,
    themePreference: String = "light",
) {
    RipDpiTheme(themePreference = themePreference) {
        ModeEditorScreen(
            uiState = uiState,
            snackbarHostState = remember { SnackbarHostState() },
            actions = NoOpModeEditorActions,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ModeEditorScreenPreview() {
    val draft =
        AppSettingsSerializer.defaultValue.toConfigDraft().copy(
            mode = Mode.VPN,
            dnsIp = "1.1.1.1",
            proxyIp = "127.0.0.1",
            proxyPort = "1080",
            maxConnections = "512",
            bufferSize = "16384",
        )
    ModeEditorScreenWithNoOpCallbacks(
        uiState =
            ConnectionUiState(
                activeMode = draft.mode,
                presets = buildConfigPresets(draft),
                editingPreset = ConnectionPreset(id = "custom", kind = ConnectionPresetKind.Custom, draft = draft),
                draft = draft,
            ),
    )
}

@Preview(showBackground = true)
@Composable
private fun ModeEditorScreenDarkPreview() {
    val draft =
        AppSettingsSerializer.defaultValue.toConfigDraft().copy(
            mode = Mode.Proxy,
            dnsIp = "1.1.1.1",
            proxyIp = "10.0.0.14",
            proxyPort = "1085",
            maxConnections = "1024",
            bufferSize = "32768",
            chainDsl = "[tcp]\nfake host+1\nsplit midsld\n\n[udp]\nfake_burst 2",
            defaultTtl = "12",
            useCommandLineSettings = true,
            commandLineArgs = "--fake --ttl 12 --split 2",
        )
    ModeEditorScreenWithNoOpCallbacks(
        uiState =
            ConnectionUiState(
                activeMode = draft.mode,
                presets = buildConfigPresets(draft),
                editingPreset =
                    ConnectionPreset(id = "recommended", kind = ConnectionPresetKind.Recommended, draft = draft),
                draft = draft,
                validationErrors =
                    persistentMapOf(
                        ConnectionFieldDnsIp to "invalid_dns_ip",
                        ConnectionFieldDefaultTtl to "out_of_range",
                    ),
            ),
        themePreference = "dark",
    )
}
