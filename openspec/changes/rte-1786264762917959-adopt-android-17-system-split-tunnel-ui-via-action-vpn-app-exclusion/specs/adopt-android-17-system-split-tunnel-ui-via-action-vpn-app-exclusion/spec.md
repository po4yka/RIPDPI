## Purpose

Define the observable completion contract for Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS. Android 17 added a system-owned split-tunnel UI: VPN apps fire ACTIONVPNAPPEXCLUSIONSETTINGS and the OS persists user exclusions across reconnects. Wire this from RIPDPI settings so the per-app exclusion state lives in the OS instead of in-app, reducing the risk of exclusion loss on reconnect

## ADDED Requirements

### Requirement: REQ-RTE-1786264762917959-001 — Settings screen on Android 17+ fires ACTIONVPNAPPEXCLUSIONSETTINGS to delegate…

The RIPDPI implementation MUST satisfy this portfolio criterion: Settings screen on Android 17+ fires ACTIONVPNAPPEXCLUSIONSETTINGS to delegate to OS UI. The split-tunnel screen shows a "managed by system" card whose button fires the intent (the verified compileSdk=37 value android.settings.VPNAPPEXCLUSIONSETTINGS), gated….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Settings screen on Android 17+ fires ACTIONVPNAPPEXCLUSIONSETTINGS to delegate to OS UI. The split-tunnel screen shows a "managed by system" card whose button fires the intent (the verified compileSdk=37 value android.settings.VPNAPPEXCLUSIONSETTINGS), gated…

### Requirement: REQ-RTE-1786264762917959-002 — Android < 17 fallback retains in-app exclusion UI. The in-app editor is shown o…

The RIPDPI implementation MUST satisfy this portfolio criterion: Android < 17 fallback retains in-app exclusion UI. The in-app editor is shown on < 17 and whenever the system screen does not resolve on the device (graceful degradation, no dead button).

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Android < 17 fallback retains in-app exclusion UI. The in-app editor is shown on < 17 and whenever the system screen does not resolve on the device (graceful degradation, no dead button)

### Requirement: REQ-RTE-1786264762917959-003 — Delegate editing to Android Settings

RIPDPI MUST delegate editing to Android Settings where available. Android owns persistence for its settings.

#### Scenario: Open Android's exclusion settings

- **WHEN** the user opens split-tunnel settings on Android 17 or later and Android can open its exclusion settings
- **THEN** RIPDPI shows the system settings action and uses Android Settings to edit exclusions

### Requirement: REQ-RTE-1786264762917959-004 — Start the system action with no receiving intent filter

RIPDPI MUST start the Android exclusion settings action. Its manifest MUST NOT declare an activity that receives that action.

#### Scenario: Start exclusion settings from the app

- **WHEN** the user selects the system exclusion settings action
- **THEN** RIPDPI starts the system activity with `ACTION_VPN_APP_EXCLUSION_SETTINGS`
