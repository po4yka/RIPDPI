# Change: Fix finalmask boundary failures

Task ID: `TRN-1794371392084141`

## Why

TRN-06 found unbounded allocation, password changes, stalled error cleanup, inconsistent validation and silent mask disablement. The recorded reference does not contain the supported codec.

## What Changes

- Bound fragment count and allocation, preserve exact password bytes and terminate failed bridge tasks.
- Reject malformed active settings at admission; ignore inactive mask fields.
- Reject unknown nonempty types instead of replacing them with off.
- Record exact peer requirements and runnable examples. Previously accepted invalid configurations can now fail early.

## Capabilities

### New Capabilities

- `finalmask-boundary`: consistent mask admission, bounded resources and explicit peer contracts.

### Modified Capabilities

- None.

## Impact

Rust ripdpi-xhttp and ripdpi-relay-core; Kotlin settings, service and app validation. No new dependencies, schema fields, locale keys, release or external probes.
