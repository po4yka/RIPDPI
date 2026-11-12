# Change: Repair HostFake payload and execution contracts

Task ID: `RST-1794499062183726`

## Why

HostFake can misreport split fallback, modify genuine packet options and replace TLS record headers as SNI bytes. Android validation and saved recommendations can also disagree with native execution.

## What Changes

- Correct receipts, raw requirement text, MD5 roles, sequence wrap and SNI framing.
- Align ASCII validation and preserve random mode in saved configuration and DSL.
- Make errors after a raw send attempt terminal to prevent replay.
- No breaking external protocol change, dependency or automatic root activation.

## Capabilities

### New Capabilities

- `hostfake-contracts`: HostFake payload, fallback and configuration invariants.

### Modified Capabilities

- None.

## Impact

Rust config/desync/privileged crates and Kotlin data/settings/engine/UI; additive default-false StrategyTcpStep field. No release or deployment.
