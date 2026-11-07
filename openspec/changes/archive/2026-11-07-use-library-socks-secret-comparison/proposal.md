# Change: Use library constant-time SOCKS credential comparison

Task ID: `RLY-1794036580419313`

## Why

SOCKS authentication must use the existing maintained constant-time comparison primitive in both runtime paths. The 2026-11-07 audit identifies this as finding 7 at source revision 202f970.

## What Changes

- Replace the relay-core XOR fold with constant_time_eq::constant_time_eq, already used by proxy-runtime.
- Correct username and password authenticate through the credentialed listener.
- An incorrect username or password is rejected without logging either credential.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Evaluate username and password comparisons without short-circuit; preserve unequal-length rejection and redacted authentication errors. Do not claim to hide credential lengths.

## Capabilities

### New Capabilities

- `standard-components/use-library-socks-secret-comparison`: SOCKS authentication must use the existing maintained constant-time comparison primitive in both runtime paths.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `native/rust/crates/ripdpi-relay-core/src/socks/auth.rs`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
