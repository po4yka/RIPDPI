# Change: Fix QUIC CRYPTO split audit findings

Task ID: `ENG-1794638026548237`

## Why

The DPI-19 audit found payload loss after optional send errors, lost TTL policy, ambiguous CRYPTO normalization, unbounded counts, and invalid small Initial preludes.

## What Changes

- Keep original sends and truthful fallback evidence when optional preludes cannot be used.
- Preserve fixed TTL policy and bound CRYPTO split work.
- Reject conflicting CRYPTO data and short standalone client Initial input.

## Capabilities

### New Capabilities

- `quic-split-safety`: bounded and byte-preserving QUIC decoy preludes.

### Modified Capabilities

- None.

## Impact

Rust packets, config, desync, proxy config/runtime adapters and Kotlin data validation change. Counts above 64, or combined CRYPTO split counts above 64, become invalid. Existing accepted small counts and storage/wire schemas remain stable. No new dependency is required.
