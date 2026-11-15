# Change: Correct Telegram diagnostic evidence and transport

Task ID: `DGN-1794734107413188`

## Why

The Telegram diagnostic can report availability after all configured data centers
fail. Unverified HTTP peers, mixed results, and interrupted probes can also
produce misleading results in a scan report.

## What Changes

- Validate HTTPS targets and distinguish website access from media transfer.
- Authenticate the HTTP upload peer and keep failure causes separate.
- Report each configured TCP endpoint and the WSS opening handshake separately.
- Preserve measured results when the user cancels or a budget expires.
- Display coverage, availability, and the short report from the same facts.

## Capabilities

### New Capabilities

- `diagnostics/telegram-evidence` records HTTP, TCP, and WSS measurements.

### Modified Capabilities

- None.

## Impact

Native diagnostics, the Android catalog and preflight, report types, and the
Telegram card and share text. BREAKING: A missing quality score stays absent.
