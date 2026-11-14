# Change: Repair DPI-35 Lua execution contracts

Task ID: `RST-1794684393149595`

## Why

The DPI-35 audit found six contract failures in sandbox Lua loading, packet results, live-flow lifetime, UI dependencies, activation errors and clock values. Profiles can appear valid while processing is absent or repeated.

## What Changes

- Reject external binary chunks in the sandbox and preserve text scripts.
- Materialize payload MODIFY results and preserve supported transport limits.
- Retain admitted flow state. At capacity, latch new-flow bypass until the engine is rebuilt; do not start skipped flows on later packets.
- Save bundled library dependencies in order and extract assets for the selected owner.
- Fail TUN activation on configuration errors and return ABI-compatible clock values.
- BREAKING: internal TUN interceptor construction becomes fallible; invalid profiles no longer silently pass traffic.

## Capabilities

### New Capabilities

- `lua-execution-safety`: Consistent sandbox input, flow lifetime, packet output and activation contracts.

### Modified Capabilities

- None. Existing configuration and JNI wire schemas stay unchanged.

## Impact

- strategy-lua, proxy-runtime, tunnel-intercept, tunnel-core and affected test callers.
- Android strategy editor and service Lua asset extraction.
- No new dependencies, privileged activation, baseline changes or upstream nfqws2 changes.
