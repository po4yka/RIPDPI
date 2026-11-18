# Change: Add bounded TLS interference hop comparisons

Task ID: `DGN-1795014015067016`

## Why

Current platform_ttl reads the local configured outbound TTL, not router responses or interference location. An opt-in paired control/test SNI hop experiment is a distinct diagnostic, not a reinterpretation of that value.

## What Changes

- Add the bounded diagnostic behavior specified below, manually initiated and offline-first.
- Preserve current default behavior and distinguish observations from cause hypotheses.

## Capabilities

### New Capabilities

- `tls-interference-hop-comparison`: scoped diagnostic comparison evidence.

### Modified Capabilities

- None; integrate through existing diagnostic owners without weakening their guarantees.

## Impact

Existing native diagnostics runner/probes/contracts/classification, core diagnostics/history/export and manual diagnostic UI. Future schema changes require synchronized Rust/Kotlin updates. No new required backend.

## Sources

https://ooni.org/post/2026-ooni-probe-tls-middlebox-experiment/

OONI describes paired control and test names with expiring requests. Its advanced path data can expose location; this proposal adopts local-first measurements instead of OONI publication.
