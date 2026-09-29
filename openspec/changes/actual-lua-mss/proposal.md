# Change: Pass actual sending MSS to Lua

Task ID: `RST-1790682603953151`

## Why

Lua always receives 1460. Socket TCP_INFO already supplies the current send MSS, but the strategy context discards it. Segmentation can exceed the negotiated send limit.

## What Changes

- Carry optional measured send MSS through the internal strategy context.
- Read the physical sending socket on each TCP Lua invocation.
- Retain 1460 when measurement is unavailable or invalid.
- Preserve UDP, logical destination matching, and the root backend.

## Capabilities

### New Capabilities

- `actual-lua-mss`: measured socket MSS for Lua segmentation.

### Modified Capabilities

- None.

## Impact

Strategy trait, Lua engine, socket adapter, proxy runtime and TUN context construction change. No wire, JNI, storage, Kotlin or production dependency changes. The internal Rust struct gains an optional field; explicit constructors require adjustment.
