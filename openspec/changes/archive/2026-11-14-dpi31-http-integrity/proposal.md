# Change: Preserve HTTP data and expose mutation outcomes

Task ID: `ENG-1794677657331523`

## Why

The DPI-31 audit found that Method EOL removes meaningful User-Agent bytes to keep payload length unchanged. It also found that an HTTP mutation can change bytes while the chain execution receipt reports ActivationSkipped. Already-prefixed requests can lose protocol metadata after another prefix is added.

## What Changes

- Preserve significant User-Agent and body bytes. Leave unsafe Method EOL requests unchanged.
- Decline Method EOL on an already-prefixed request.
- Expose HTTP mutation outcome separately from TCP chain disposition through required receipt consumers.
- No breaking stored configuration or dependency change is planned. Any additive receipt contract change must keep current chain disposition semantics.

## Capabilities

### New Capabilities

- `dpi31-http-integrity`: data preservation and independent HTTP mutation outcome contracts.

### Modified Capabilities

- None.

## Impact

Rust HTTP packets, desync planning, desync execution receipts, and required receipt consumers. The integrator owns API snapshot regeneration and task state. No new protocol, root feature, bypass capability, locale, baseline, golden, lockfile, or backend service is included.
