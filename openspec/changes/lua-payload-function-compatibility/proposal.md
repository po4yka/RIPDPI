# Change: Complete zapret2 Lua compatibility

Task ID: `RST-1790672261516782`

## Why

The payload planner lacks the packet headers, conntrack, replay, timers, and native helpers needed by the full zapret2 Lua set. The user requested full parity and approved a separate root nfqws2 backend and its netfilter dependencies.

## What Changes

- Provide the complete pinned zapret2 runtime and six scripts through an opt-in root NFQUEUE backend.
- Own backend start, supervision, queue rules, stop, and failure cleanup in the existing service lifecycle.
- Preserve non-root operation and reject packet capabilities that ordinary sockets cannot provide.
- Correct shared payload position, classification, arithmetic, and state helpers. BREAKING: numeric position strings follow upstream zero-based byte offsets.

## Capabilities

### New Capabilities

- `lua-payload-function-compatibility`: full pinned upstream root behavior and safe payload-only operation without root.

### Modified Capabilities

- None.

## Impact

- Native build and packaging, core engine assets, core service lifecycle, strategy-lua, root configuration, tests, and documentation.
- New pinned libnetfilter_queue, libnfnetlink, and libmnl dependencies, with source and license notices. No backend service or telemetry dependency.
