# Correct DIA-19 local proxy and Xray diagnostics

Task ID: `DGN-1794755310955921`

## Why

Local proxy and Xray checks can exceed selected ports and network permission.
The results can overstate evidence or disclose outbound metadata.

## What Changes

Fix F1–F12 of the DIA-19 audit in independent changes. Cover settings,
planning, protocol I/O, lifecycle, classification, display, and export.

## Capabilities

### New Capabilities

- `diagnostics/local-proxy-evidence`: local proxy and Xray API observations.

### Modified Capabilities

None.

## Impact

Kotlin Detection settings, probes, verdict and reports. Portfolio task
[DGN-1794755310955921](../../../docs/tasks/issues/correct-dia19-diagnostics.md).
