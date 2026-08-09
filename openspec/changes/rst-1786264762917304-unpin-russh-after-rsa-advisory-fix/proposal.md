# Change: Unpin russh after rsa advisory fix

Task ID: `RST-1786264762917304`

## Why

native/rust/Cargo.toml pins russh at exactly =0.62.5 and native/rust/deny.toml suppresses RUSTSEC-2023-0071 (rsa Marvin timing sidechannel) with the justification that:

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `unpin-russh-after-rsa-advisory-fix`: Unpin russh after rsa advisory fix

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
