package com.poyka.ripdpi.ui.screens.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.RelayChainHopStatusUiState
import com.poyka.ripdpi.activities.RelayChainHopUiState
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayTrustDomainWarning
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import kotlinx.collections.immutable.persistentListOf

/**
 * Agent-legible preview of the multi-hop relay-chain editor in a populated 3-hop state:
 * entry + one middle hop + exit, with a shared-jurisdiction trust warning and live per-hop
 * status/latency captions. Throwaway render only — never copied into goldens.
 */
@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RelayChainEditorFieldsPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RelayChainFields(
            draft =
                ConfigDraft(
                    relayKind = RelayKindChainRelay,
                    relayChainEntryProfileId = "entry",
                    relayChainExitProfileId = "exit",
                    relayChainMiddleProfileIds = persistentListOf("relay-amsterdam"),
                ),
            uiState =
                ConfigUiState(
                    relayProfiles =
                        persistentListOf(
                            RelayProfileUiState(
                                id = "entry",
                                kind = RelayKindVlessReality,
                                kindLabel = "VLESS + Reality",
                                jurisdiction = "DE",
                                operatorName = "Frankfurt Transit",
                            ),
                            RelayProfileUiState(
                                id = "relay-amsterdam",
                                kind = RelayKindVlessReality,
                                kindLabel = "VLESS + Reality",
                                jurisdiction = "NL",
                                operatorName = "Amsterdam Transit",
                            ),
                            RelayProfileUiState(
                                id = "exit",
                                kind = RelayKindMasque,
                                kindLabel = "MASQUE",
                                jurisdiction = "DE",
                                operatorName = "Berlin Exit",
                            ),
                        ),
                    relayChainHopStatus =
                        RelayChainHopStatusUiState(
                            entry = RelayChainHopUiState(statusLabel = "Connected", latencyLabel = "24 ms"),
                            exit = RelayChainHopUiState(statusLabel = "Connecting", latencyLabel = "61 ms"),
                        ),
                    relayChainTrustWarning = RelayTrustDomainWarning(sharedJurisdiction = "DE"),
                ),
            onRelayChainHopProfileIdChanged = { _, _ -> },
            onRelayChainHopAdded = {},
            onRelayChainHopRemoved = {},
            onRelayChainHopMoved = { _, _ -> },
        )
    }
}
