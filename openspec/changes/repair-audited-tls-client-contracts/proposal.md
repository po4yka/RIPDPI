# Change: Repair audited TLS client contracts

Task ID: `TRN-1794158908788387`

## Why

The STD-01 audit reproduced plaintext TLS diagnostics, ignored DoT pins, incompatible ALPN use, shared DoQ timeout failures and an ineffective TLS handshake deadline. It also found a TUIC early-data setting without a working implementation.

## What Changes

- Preserve explicit TLS intent on every port.
- Enforce pinned peer policy and the negotiated application protocol.
- Isolate DNS query cancellation and bound TLS diagnostic workers.
- Make unsupported TUIC early data explicit instead of silently treating it as active.
- Keep one tested code commit for each confirmed finding.
- BREAKING: configurations that request an unsupported TUIC early-data mode may be rejected explicitly; no stored schema changes are planned.

## Capabilities

### New Capabilities

- `tls-client-contracts`: TLS intent, authentication, ALPN and cancellation contracts for the audited consumers.

### Modified Capabilities

- None.

## Impact

- Native diagnostics TLS/fat-header, monitor connection-concurrency, DNS resolver, MASQUE, xHTTP and TUIC.
- Kotlin TUIC validation only if needed to make the existing setting truthful.
- No new production dependency, credential, external service or release artifact.
