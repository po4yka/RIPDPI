# Change: Deliver actionable diagnostics and verified selector activation

Task ID: `SVC-1791025113925875`

## Why

Diagnostic recommendation routes are produced but never rendered as actions. Selector selection is persisted separately from runtime activation; the current refresh tears down the VPN and can cancel its own restart. Ranking currently measures only a TCP connection to the server, rather than the candidate transport carrying a payload. Obsolete selectors, empty automatic fixes and compatibility interfaces obscure these working paths.

## What Changes

- Users can open the appropriate settings or diagnostic workflow from an evidence-based recommendation, without automatic policy mutation.
- Offline selector choices apply on the next runtime start and active choices are reconciled through service-owned session control.
- Automatic ranking consumes scoped transport payload evidence; cancelled or stale results cannot overwrite manual selection, and Cloudflare stays manual-only.
- BREAKING: remove obsolete Kotlin aliases, dormant automatic-fix types and native reexport crate interfaces; update every repository consumer to the canonical owner.

## Capabilities

### New Capabilities

- `actionable-diagnostics-selector`: user-directed recommendations, durable selector activation and candidate transport payload validation.

### Modified Capabilities

- None; existing selection provenance and Cloudflare exclusion remain authoritative.

## Impact

- app, core/detection, core/service, core/data/settings/runtime-state and core/diagnostics; native monitor/proxy crate dependencies.
- No new production dependencies, backend, JNI/protobuf/wire schema, credential authority, or golden blessing.
