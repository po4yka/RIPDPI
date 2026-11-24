# Change: Migrate Android runtime behavior to target SDK 37

Task ID: `AND-1787932839013427`

## Why

The app compiles against API 37 but targets API 35. Runtime migration needs working
local-network permission recovery, TLS trust enforcement, and Android 16/17 coverage.

## What Changes

- BREAKING: target SDK 37 enables Android 16/17 behavior and LAN permission enforcement.
- Request LAN access only for dependent operations; retain public and same-profile loopback traffic.
- Preserve TLS trust failures across client fallback; use current platform ECH XML modes.
- Add API 36/37 tests without replacing existing API 27/33/35 coverage.

## Capabilities

### New Capabilities

- `android-target-37`: SDK 37 runtime, permission and transport acceptance.

### Modified Capabilities

None. Existing ECH and system split-tunnel tasks retain their independent acceptance gates.

## Impact

App permission/UI flows, service admission and HTTP fallback, diagnostics, native errors,
SDK properties, test dependency catalog, managed devices, CI, and ten locale resources.
