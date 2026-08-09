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

### Requirement: REQ-RTE-1786264762917959-003 — Exclusions verified to persist across VPN reconnects (OS-managed state). DEVICE…

The RIPDPI implementation MUST satisfy this portfolio criterion: Exclusions verified to persist across VPN reconnects (OS-managed state). DEVICE-GATED — persistence is OS-owned and only observable on a real Android 17 device.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Exclusions verified to persist across VPN reconnects (OS-managed state). DEVICE-GATED — persistence is OS-owned and only observable on a real Android 17 device

### Requirement: REQ-RTE-1786264762917959-004 — Manifest declares supported intent for system discovery. CORRECTED: Android 17…

The RIPDPI implementation MUST satisfy this portfolio criterion: Manifest declares supported intent for system discovery. CORRECTED: Android 17 defines no app-side manifest declaration for this — ACTIONVPNAPPEXCLUSIONSETTINGS is a system Settings action the app fires (via startActivity), not one a third-party Activity rece….

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Manifest declares supported intent for system discovery. CORRECTED: Android 17 defines no app-side manifest declaration for this — ACTIONVPNAPPEXCLUSIONSETTINGS is a system Settings action the app fires (via startActivity), not one a third-party Activity rece…
