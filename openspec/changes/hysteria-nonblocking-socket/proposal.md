# Change: Admit Hysteria UDP sockets into the async runtime

Task ID: `RST-1792838334204120`

## Why

Physical Hysteria acceptance reached Running, then the native runtime panicked when Tokio received a blocking Salamander socket. The same constructor serves initial endpoint creation and endpoint rebind. Data transfer cannot complete until socket admission meets the async runtime contract.

## What Changes

- Accept a bound blocking or nonblocking UDP socket and make it ready for asynchronous Salamander traffic.
- Preserve the bound socket, socket protection, endpoint rebind, and obfuscation behavior.
- No public contract or dependency change.

## Capabilities

### New Capabilities

- `hysteria-async-socket-admission`: safe admission of bound UDP sockets for initial endpoints and rebind.

### Modified Capabilities

- None.

## Impact

- `native/rust/crates/ripdpi-hysteria2/src/salamander.rs` and endpoint regression tests.
- No broader port hopping feature, Android configuration change, or deployment action.
