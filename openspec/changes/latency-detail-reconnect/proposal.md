# Change: Open measured latency details and verify real reconnect

Task ID: `UIX-1792294575616838`

## Why

Home shows measured RTT but does not open its evidence directly. Reconnect review
has UI tests but no observed service and network acceptance from its confirmation.
Users need a clear detail path and verified, truthful application feedback.

## What Changes

- Open the selected latency measurement in an accessible detail surface.
- Preserve the clicked sample, its source and measurement window while reading.
- Keep Back, dismissal and return focus correct with normal, reduced and disabled motion.
- Verify cancellation and confirmation through the actual service and native path,
  including network traffic before and after reconnect.
- No breaking change. No future-route preview or invented time series is added.

## Capabilities

### New Capabilities

- `ui/latency-detail-reconnect`: measured latency drill-down and real reconnect acceptance.

### Modified Capabilities

- None.

## Impact

- App presentation, opt-in metric actions, localized labels and focused tests.
- Emulator integration tests and local acceptance receipts.
- No new dependency, storage, wire, JNI or service contract is planned.
