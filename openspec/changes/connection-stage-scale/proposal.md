# Change: Unify diagnostic connection stages

Task ID: `DGN-1791523992124084`

## Why

Probe families expose different stage names. Users cannot compare the point reached by each connection or distinguish an unmeasured stage from a failed stage.

## What Changes

- Show one stage vocabulary for DNS, TCP, proxy negotiation, TLS, HTTP headers, first body byte, body transfer and QUIC response.
- Keep separate attempts, TLS profiles, service branches and HTTP connections separate.
- Preserve unknown, not applicable, not reached, interrupted and partial states without a provider-cause claim.
- Use the same projection for current results, history, live transfer evidence and text exports.

## Capabilities

### New Capabilities

- `connection-stage-scale`: Evidence-based connection stage presentation and export.

### Modified Capabilities

- None.

## Impact

- `core/diagnostics` projection and exports; `app` UI and ten locales.
- Existing native evidence and persisted records remain compatible. No wire, storage or dependency change is required.
