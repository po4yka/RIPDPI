# Correct DIA-20 local detection evidence

Task ID: `DGN-1794755305945452`

## Why

DIA-20 can attribute local TLS settings to an active VPN and name an installed package as the VPN owner. Failed reads can appear as positive detection. Weak package clues can raise the verdict. Reports also expose inventory in private mode or omit the TLS result.

## What Changes

- Preserve local TLS read status and label reference coverage and configured presets accurately.
- Keep package inventory, active VPN identity and weak name clues separate.
- Bound package and APK inspection. Label filename clues and retain partial results.
- Remove app details from private exports. Include TLS status in each report format.

## Capabilities

### New Capabilities

- `diagnostics/local-detection-evidence` owns the local TLS and package inventory contract.

### Modified Capabilities

None.

## Impact

Changes core:detection output and the Detection UI. Reuses the existing execution status. No dependency, native wire, settings or database change.
