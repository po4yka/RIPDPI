# Correct DIA-10 diagnostic evidence

Task ID: `DGN-1794739971189458`

## Why

DIA-10 can report a comparison without a test endpoint, report a transport as available after a component check, and hide incomplete or inconsistent measurements.

## What Changes

Align the throughput entry with the controls it measures. Verify STUN Binding replies, cancel active I/O, and show each PT operation. Separate service response evidence from unknown results. Preserve circumvention retry evidence and give NAT64 candidates separate time. Identify unstable selective matrix targets.

## Capabilities

### New Capabilities

- Add `diagnostics/probe-evidence` requirements for DIA-10 results.

## Impact

The connectivity runners and the diagnostic UI change. The existing network targets and protocol limits apply.
