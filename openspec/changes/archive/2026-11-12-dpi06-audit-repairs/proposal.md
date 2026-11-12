# Change: Repair DPI-06 execution safety

Task ID: `RST-1794498457070491`

## Why

The audit found unsafe page cleanup, inconsistent TTL, wrong receipts, lost secondary payloads, rejected sequence wrap, and conflicting documentation.

## What Changes

Repair F1 through F7 in separate commits. Preserve real bytes and known send progress on error. Restore socket state, preserve selected payloads, and report the effective operation.

## Capabilities

### New Capabilities

- `tcp-fake-execution-safety`: define data-safety and evidence obligations for the existing sender.

### Modified Capabilities

- None.

## Impact

Rust privileged operations, desync runtime, helper error data, runtime adapter, and documentation. No new dependency or top-level configuration, JNI, protobuf, storage, or helper wire field is planned. Existing optional HelperResponse.data may carry known progress on failure.
