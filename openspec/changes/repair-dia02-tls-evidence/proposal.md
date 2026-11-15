# Change: Preserve audited TLS probe evidence

Task ID: `DGN-1794730805168562`

## Why

DIA-02 found that TLS diagnostics can lose a recorded ClientHello, combine evidence from different connections, and misstate the cause of a failure.

## What Changes

- Preserve captured JA3 and transport, negotiation, and trust evidence on each TLS attempt.
- Report ECH configuration source, peer binding, retry, and cancellation explicitly.
- BREAKING: Tighten the meaning of legacy failure and response signals. Update in-repository policy readers with the producers.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `diagnostics-tls-evidence` — per-attempt measurement, attribution, and bounded capture.

## Impact

The diagnostics TLS crate records attempts. The monitor, connectivity runner, and Kotlin diagnostics consumers use this evidence. No change to relay TLS, JA4 computation, or browser emulation.
