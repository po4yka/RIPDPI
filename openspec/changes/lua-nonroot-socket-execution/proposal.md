# Change: Execute Lua payload plans without root

Task ID: `RST-1790669115219534`

## Why

Lua strategies currently reach the TUN executor, which sends their payload actions through raw IP sockets. Non-root Android rejects these sends. Replacement DROP can then consume the original packet even when injection failed.

## What Changes

- Route non-root Lua payload strategies to existing protected proxy sockets.
- Validate complete plans before sending; keep unsupported packet mutations on the root path or apply on_fail.
- Keep per-flow Lua state and release it with its session.
- Preserve the original TUN packet after replacement injection failure.

## Capabilities

### New Capabilities
- `nonroot-lua-payload-execution`: execute payload-only Lua plans without privileged sockets.

### Modified Capabilities
None.

## Impact

Native strategy planning, proxy runtime, Kotlin runtime-context transport, and TUN Lua ownership change. Optional runtime-context fields are additive. No protobuf, settings migration, new external dependency, or Lua asset update is required.
