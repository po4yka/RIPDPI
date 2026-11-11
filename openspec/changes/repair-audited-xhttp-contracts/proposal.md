# Change: Repair audited xHTTP stream and pool contracts

Task ID: `TRN-1794371418861139`

## Why

The TRN-04 audit reproduced blocked stream-up opening, detached HTTP tasks after stream drop, changed path/query values and a pool waiter that ignores available carriers.

## What Changes

- Open stream-up without waiting for upload response headers, and report asynchronous upload failures.
- Close each stream's owned HTTP tasks on full drop while preserving write-half shutdown and active siblings.
- Preserve configured URL path and query semantics and use any available carrier capacity.
- Correct mode and upstream pin documentation. No breaking configuration changes.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `http-relay-carrier-contracts`: add xHTTP opening, teardown, URL and pool requirements.

## Impact

- Rust `ripdpi-xhttp` and its regression tests; existing relay-core callers keep their API.
- No dependencies, serialized schema, Kotlin configuration or supported-mode expansion.
